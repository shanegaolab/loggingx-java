package com.loggingx.biz.monitor;

import com.loggingx.dao.monitor.ContainerSample;
import com.loggingx.dao.monitor.FilesystemSample;
import com.loggingx.dao.monitor.HostSample;
import com.loggingx.dao.monitor.ProcessSample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the metric points of Vector's {@code host_metrics} source into one sample per host and scrape.
 * <p>
 * Per-CPU and per-device values are summed: CPU seconds of all modes (idle and I/O wait counted as idle), disk bytes
 * of whole disks (partitions and virtual devices are skipped, so nothing is counted twice) and network bytes of
 * physical interfaces (loopback and container bridges are skipped). Pseudo file systems are skipped. Of the processes,
 * the busiest by CPU and by memory are kept.
 */
public final class HostSnapshots {

    /** Processes kept per scrape and ranking. */
    static final int TOP_PROCESSES = 15;

    private static final Set<String> IDLE_MODES = Set.of("idle", "iowait");
    /** Partitions (sda1, nvme0n1p1, mmcblk0p1) and virtual block devices. */
    private static final Pattern SKIPPED_DISK = Pattern.compile(
            "(?:(?:sd|vd|xvd|hd)[a-z]+\\d+|nvme\\d+n\\d+p\\d+|mmcblk\\d+p\\d+|loop\\d+|ram\\d+|zram\\d+|dm-\\d+|sr\\d+"
                    + "|md\\d+p\\d+|nbd\\d+)");
    /** Loopback, container and virtual network interfaces. */
    private static final Pattern SKIPPED_INTERFACE = Pattern.compile(
            "(?i)(?:lo|lo\\d*|veth.*|docker\\d*|br-.*|cni\\d*|flannel.*|cali.*|vxlan.*|tunl\\d*|kube-.*|virbr.*"
                    + "|vnet\\d+|weave.*|cilium.*|lxc.*|tap.*|.*loopback.*)");
    private static final Set<String> PSEUDO_FILESYSTEMS = Set.of("tmpfs", "devtmpfs", "devfs", "overlay", "squashfs",
            "proc", "sysfs", "cgroup", "cgroup2", "nsfs", "autofs", "mqueue", "debugfs", "tracefs", "securityfs",
            "pstore", "bpf", "configfs", "fusectl", "hugetlbfs", "binfmt_misc", "rpc_pipefs", "efivarfs", "ramfs",
            "fuse.lxcfs", "nfsd", "selinuxfs", "shm", "iso9660", "udf");
    /** The Docker agent sees the host's file system below this path. */
    private static final String HOST_ROOT = "/host";

    private HostSnapshots() {
    }

    /**
     * The samples built from one request.
     */
    public record Batch(List<HostSample> hosts, List<FilesystemSample> filesystems, List<ProcessSample> processes,
                        List<ContainerSample> containers) {
    }

    private static final class Scrape {
        final String host;
        final Instant time;
        Double cpuTotal;
        Double cpuIdle;
        Double cpuCores;
        Double memTotal;
        Double memAvailable;
        Double swapTotal;
        Double swapUsed;
        Double load1;
        Double load5;
        Double load15;
        Double diskRead;
        Double diskWrite;
        Double netRx;
        Double netTx;
        Double uptime;
        final Map<String, double[]> filesystems = new LinkedHashMap<>();
        final Map<String, String> fsTypes = new HashMap<>();
        final Map<Long, String> processNames = new HashMap<>();
        final Map<Long, Double> processCpu = new HashMap<>();
        final Map<Long, Double> processMem = new HashMap<>();
        final Map<String, Double> cgroupCpu = new LinkedHashMap<>();
        final Map<String, Double> cgroupMem = new LinkedHashMap<>();

        Scrape(String host, Instant time) {
            this.host = host;
            this.time = time;
        }
    }

    private record ScrapeKey(String host, Instant time) {
    }

    public static Batch build(Long storeId, List<MetricPoint> points) {
        Map<ScrapeKey, Scrape> scrapes = new LinkedHashMap<>();
        for (MetricPoint point : points) {
            if (point.host() == null || point.time() == null || !Double.isFinite(point.value())) {
                continue;
            }
            String host = point.host().trim().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) {
                continue;
            }
            Scrape scrape = scrapes.computeIfAbsent(new ScrapeKey(host, point.time()),
                    key -> new Scrape(key.host(), key.time()));
            apply(scrape, point);
        }

        List<HostSample> hosts = new ArrayList<>();
        List<FilesystemSample> filesystems = new ArrayList<>();
        List<ProcessSample> processes = new ArrayList<>();
        List<ContainerSample> containers = new ArrayList<>();
        for (Scrape scrape : scrapes.values()) {
            boolean hasHostValues = scrape.cpuTotal != null || scrape.memTotal != null || scrape.load1 != null
                    || scrape.diskRead != null || scrape.netRx != null || scrape.uptime != null;
            if (hasHostValues) {
                Double memUsed = scrape.memTotal != null && scrape.memAvailable != null
                        ? Math.max(0, scrape.memTotal - scrape.memAvailable) : null;
                hosts.add(new HostSample(storeId, scrape.host, scrape.time, scrape.cpuTotal, scrape.cpuIdle,
                        scrape.cpuCores, scrape.memTotal, memUsed, scrape.swapTotal, scrape.swapUsed, scrape.load1,
                        scrape.load5, scrape.load15, scrape.diskRead, scrape.diskWrite, scrape.netRx, scrape.netTx,
                        scrape.uptime));
            }
            scrape.filesystems.forEach((mountpoint, values) -> {
                if (values[0] > 0) {
                    filesystems.add(new FilesystemSample(storeId, scrape.host, scrape.time, mountpoint,
                            scrape.fsTypes.get(mountpoint), values[0], values[1]));
                }
            });
            processes.addAll(topProcesses(storeId, scrape));
            Set<String> cgroups = new LinkedHashSet<>(scrape.cgroupCpu.keySet());
            cgroups.addAll(scrape.cgroupMem.keySet());
            for (String cgroup : cgroups) {
                containers.add(new ContainerSample(storeId, scrape.host, scrape.time, cgroup,
                        scrape.cgroupCpu.get(cgroup), scrape.cgroupMem.get(cgroup)));
            }
        }
        return new Batch(hosts, filesystems, processes, containers);
    }

    private static void apply(Scrape scrape, MetricPoint point) {
        double value = point.value();
        switch (point.name()) {
            case "cpu_seconds_total" -> {
                scrape.cpuTotal = add(scrape.cpuTotal, value);
                if (IDLE_MODES.contains(point.tag("mode"))) {
                    scrape.cpuIdle = add(scrape.cpuIdle, value);
                } else if (scrape.cpuIdle == null) {
                    scrape.cpuIdle = 0d;
                }
            }
            case "logical_cpus" -> scrape.cpuCores = value;
            case "memory_total_bytes" -> scrape.memTotal = value;
            case "memory_available_bytes" -> scrape.memAvailable = value;
            case "memory_swap_total_bytes" -> scrape.swapTotal = value;
            case "memory_swap_used_bytes" -> scrape.swapUsed = value;
            case "load1" -> scrape.load1 = value;
            case "load5" -> scrape.load5 = value;
            case "load15" -> scrape.load15 = value;
            case "uptime" -> scrape.uptime = value;
            case "disk_read_bytes_total" -> {
                if (countedDisk(point.tag("device"))) {
                    scrape.diskRead = add(scrape.diskRead, value);
                }
            }
            case "disk_written_bytes_total" -> {
                if (countedDisk(point.tag("device"))) {
                    scrape.diskWrite = add(scrape.diskWrite, value);
                }
            }
            case "network_receive_bytes_total" -> {
                if (countedInterface(point.tag("device"))) {
                    scrape.netRx = add(scrape.netRx, value);
                }
            }
            case "network_transmit_bytes_total" -> {
                if (countedInterface(point.tag("device"))) {
                    scrape.netTx = add(scrape.netTx, value);
                }
            }
            case "filesystem_total_bytes", "filesystem_used_bytes" -> {
                String mountpoint = mountpoint(point.tag("mountpoint"));
                String type = point.tag("filesystem");
                if (mountpoint == null || (type != null && PSEUDO_FILESYSTEMS.contains(type.toLowerCase(Locale.ROOT)))
                        || mountpoint.startsWith("/proc") || mountpoint.startsWith("/sys")
                        || mountpoint.startsWith("/dev") || mountpoint.startsWith("/run")
                        || mountpoint.startsWith("/snap/") || mountpoint.contains("/docker/")
                        || mountpoint.contains("/containerd/") || mountpoint.contains("/kubelet/pods/")) {
                    return;
                }
                double[] values = scrape.filesystems.computeIfAbsent(mountpoint, key -> new double[2]);
                values["filesystem_total_bytes".equals(point.name()) ? 0 : 1] = value;
                if (type != null) {
                    scrape.fsTypes.put(mountpoint, type);
                }
            }
            case "process_cpu_usage", "process_memory_usage" -> {
                Long pid = pid(point.tag("pid"));
                if (pid == null) {
                    return;
                }
                String name = point.tag("name");
                scrape.processNames.put(pid, name == null || name.isBlank() ? "pid " + pid : name);
                if ("process_cpu_usage".equals(point.name())) {
                    scrape.processCpu.put(pid, value);
                } else if (value > 0) {
                    scrape.processMem.put(pid, value);
                }
            }
            case "cgroup_cpu_usage_seconds_total" -> {
                String cgroup = point.tag("cgroup");
                if (cgroup != null) {
                    scrape.cgroupCpu.put(cgroup, value);
                }
            }
            case "cgroup_memory_current_bytes" -> {
                String cgroup = point.tag("cgroup");
                if (cgroup != null) {
                    scrape.cgroupMem.put(cgroup, value);
                }
            }
            default -> {
                // Other metrics (disk operation counts, network packets, ...) are not kept.
            }
        }
    }

    /**
     * @return the busiest processes by CPU, then by memory, without duplicates
     */
    private static List<ProcessSample> topProcesses(Long storeId, Scrape scrape) {
        Set<Long> kept = new LinkedHashSet<>();
        scrape.processCpu.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_PROCESSES)
                .forEach(entry -> kept.add(entry.getKey()));
        scrape.processMem.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_PROCESSES)
                .forEach(entry -> kept.add(entry.getKey()));
        return kept.stream()
                .map(pid -> new ProcessSample(storeId, scrape.host, scrape.time, pid, scrape.processNames.get(pid),
                        scrape.processCpu.get(pid), scrape.processMem.get(pid)))
                .toList();
    }

    static boolean countedDisk(String device) {
        if (device == null) {
            return false;
        }
        String name = device.startsWith("/dev/") ? device.substring(5) : device;
        return !SKIPPED_DISK.matcher(name).matches();
    }

    static boolean countedInterface(String device) {
        return device != null && !SKIPPED_INTERFACE.matcher(device).matches();
    }

    /**
     * @return the mount point as the host sees it (without the Docker agent's {@code /host} prefix)
     */
    private static String mountpoint(String mountpoint) {
        if (mountpoint == null || mountpoint.isBlank()) {
            return null;
        }
        if (mountpoint.equals(HOST_ROOT)) {
            return "/";
        }
        return mountpoint.startsWith(HOST_ROOT + "/") ? mountpoint.substring(HOST_ROOT.length()) : mountpoint;
    }

    private static Long pid(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double add(Double sum, double value) {
        return sum == null ? value : sum + value;
    }
}
