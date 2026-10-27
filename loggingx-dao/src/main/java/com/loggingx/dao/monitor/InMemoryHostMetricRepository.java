package com.loggingx.dao.monitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps host metrics in memory, for local runs and tests ({@code loggingx.log.storage=memory}). Metrics are lost on
 * restart, and each host keeps at most a day of samples.
 */
@Repository
@ConditionalOnProperty(name = "loggingx.log.storage", havingValue = "memory")
public class InMemoryHostMetricRepository implements HostMetricRepository {

    private static final Duration KEPT = Duration.ofDays(1);

    private record Key(Long storeId, String host) {
    }

    private final Map<Key, TreeMap<Instant, HostSample>> hosts = new HashMap<>();
    private final Map<Key, TreeMap<Instant, List<FilesystemSample>>> filesystems = new HashMap<>();
    private final Map<Key, TreeMap<Instant, List<ProcessSample>>> processes = new HashMap<>();
    private final Map<Key, Deque<List<ContainerSample>>> containers = new HashMap<>();

    @Override
    public synchronized void insert(List<HostSample> hostSamples, List<FilesystemSample> filesystemSamples,
                                    List<ProcessSample> processSamples, List<ContainerSample> containerSamples,
                                    int retentionDays) {
        Instant oldest = Instant.now().minus(KEPT);
        for (HostSample sample : hostSamples) {
            TreeMap<Instant, HostSample> samples = hosts.computeIfAbsent(key(sample.storeId(), sample.host()),
                    key -> new TreeMap<>());
            samples.merge(sample.time(), sample, HostSample::merge);
            samples.headMap(oldest).clear();
        }
        group(filesystemSamples, sample -> key(sample.storeId(), sample.host()), FilesystemSample::time)
                .forEach((key, byTime) -> {
                    TreeMap<Instant, List<FilesystemSample>> samples =
                            filesystems.computeIfAbsent(key, k -> new TreeMap<>());
                    byTime.forEach((time, list) -> samples.merge(time, list, InMemoryHostMetricRepository::concat));
                    samples.headMap(oldest).clear();
                });
        group(processSamples, sample -> key(sample.storeId(), sample.host()), ProcessSample::time)
                .forEach((key, byTime) -> {
                    TreeMap<Instant, List<ProcessSample>> samples = processes.computeIfAbsent(key, k -> new TreeMap<>());
                    byTime.forEach((time, list) -> samples.merge(time, list, InMemoryHostMetricRepository::concat));
                    samples.headMap(oldest).clear();
                });
        group(containerSamples, sample -> key(sample.storeId(), sample.host()), ContainerSample::time)
                .forEach((key, byTime) -> {
                    Deque<List<ContainerSample>> scrapes = containers.computeIfAbsent(key, k -> new ArrayDeque<>());
                    byTime.forEach((time, list) -> {
                        if (!scrapes.isEmpty() && scrapes.peekFirst().get(0).time().equals(time)) {
                            scrapes.addFirst(concat(scrapes.pollFirst(), list));
                        } else {
                            scrapes.addFirst(list);
                        }
                    });
                    while (scrapes.size() > 2) {
                        scrapes.pollLast();
                    }
                });
    }

    @Override
    public synchronized List<HostSample> latestPairs(Instant since) {
        List<HostSample> result = new ArrayList<>();
        for (TreeMap<Instant, HostSample> samples : hosts.values()) {
            samples.tailMap(since, true).descendingMap().values().stream().limit(2).forEach(result::add);
        }
        return result;
    }

    @Override
    public synchronized List<HostSample> samplesSince(Instant since) {
        List<HostSample> result = new ArrayList<>();
        for (TreeMap<Instant, HostSample> samples : hosts.values()) {
            result.addAll(samples.tailMap(since, true).values());
        }
        return result;
    }

    @Override
    public synchronized List<HostSample> series(Long storeId, String host, Instant from, Instant to,
                                                Duration bucket) {
        TreeMap<Instant, HostSample> samples = hosts.get(key(storeId, host));
        if (samples == null) {
            return List.of();
        }
        Map<Long, List<HostSample>> buckets = samples.subMap(from, true, to, false).values().stream()
                .collect(Collectors.groupingBy(sample -> sample.time().toEpochMilli() / bucket.toMillis(),
                        TreeMap::new, Collectors.toList()));
        return buckets.values().stream().map(InMemoryHostMetricRepository::aggregate).toList();
    }

    @Override
    public synchronized List<FilesystemUsage> filesystemSeries(Long storeId, String host, Instant from, Instant to,
                                                               Duration bucket) {
        TreeMap<Instant, List<FilesystemSample>> samples = filesystems.get(key(storeId, host));
        if (samples == null) {
            return List.of();
        }
        Map<Long, Double> buckets = new TreeMap<>();
        samples.subMap(from, true, to, false).forEach((time, list) -> {
            long index = time.toEpochMilli() / bucket.toMillis();
            double used = list.stream().mapToDouble(FilesystemSample::usedPercent).max().orElse(0);
            buckets.merge(index, used, Math::max);
        });
        return buckets.entrySet().stream()
                .map(entry -> new FilesystemUsage(Instant.ofEpochMilli(entry.getKey() * bucket.toMillis()),
                        entry.getValue()))
                .toList();
    }

    @Override
    public synchronized List<FilesystemSample> latestFilesystems(Instant since) {
        List<FilesystemSample> result = new ArrayList<>();
        for (TreeMap<Instant, List<FilesystemSample>> samples : filesystems.values()) {
            Map.Entry<Instant, List<FilesystemSample>> latest = samples.lastEntry();
            if (latest != null && !latest.getKey().isBefore(since)) {
                result.addAll(latest.getValue());
            }
        }
        return result;
    }

    @Override
    public synchronized List<ProcessSample> processesAt(Long storeId, String host, Instant at, Duration maxAge) {
        TreeMap<Instant, List<ProcessSample>> samples = processes.get(key(storeId, host));
        if (samples == null) {
            return List.of();
        }
        Map.Entry<Instant, List<ProcessSample>> entry = samples.floorEntry(at);
        return entry == null || entry.getKey().isBefore(at.minus(maxAge)) ? List.of() : List.copyOf(entry.getValue());
    }

    @Override
    public synchronized List<ContainerSample> latestContainers(Long storeId, String host, Instant since) {
        Deque<List<ContainerSample>> scrapes = containers.get(key(storeId, host));
        if (scrapes == null) {
            return List.of();
        }
        return scrapes.stream().flatMap(List::stream).filter(sample -> !sample.time().isBefore(since)).toList();
    }

    @Override
    public synchronized void deleteByStore(Long storeId) {
        hosts.keySet().removeIf(key -> Objects.equals(key.storeId(), storeId));
        filesystems.keySet().removeIf(key -> Objects.equals(key.storeId(), storeId));
        processes.keySet().removeIf(key -> Objects.equals(key.storeId(), storeId));
        containers.keySet().removeIf(key -> Objects.equals(key.storeId(), storeId));
    }

    /**
     * Counters at their largest value, totals at their largest value, gauges averaged, at the last sample's time.
     */
    private static HostSample aggregate(List<HostSample> samples) {
        HostSample last = samples.stream().max(Comparator.comparing(HostSample::time)).orElseThrow();
        return new HostSample(last.storeId(), last.host(), last.time(),
                max(samples, HostSample::cpuTotal), max(samples, HostSample::cpuIdle),
                max(samples, HostSample::cpuCores), max(samples, HostSample::memTotal),
                avg(samples, HostSample::memUsed), max(samples, HostSample::swapTotal),
                avg(samples, HostSample::swapUsed), avg(samples, HostSample::load1),
                avg(samples, HostSample::load5), avg(samples, HostSample::load15),
                max(samples, HostSample::diskReadBytes), max(samples, HostSample::diskWriteBytes),
                max(samples, HostSample::netRxBytes), max(samples, HostSample::netTxBytes),
                max(samples, HostSample::uptime));
    }

    private static Double max(List<HostSample> samples, Function<HostSample, Double> value) {
        return samples.stream().map(value).filter(Objects::nonNull).max(Double::compare).orElse(null);
    }

    private static Double avg(List<HostSample> samples, Function<HostSample, Double> value) {
        List<Double> values = samples.stream().map(value).filter(Objects::nonNull).toList();
        return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static <T> Map<Key, Map<Instant, List<T>>> group(List<T> samples, Function<T, Key> key,
                                                             Function<T, Instant> time) {
        Map<Key, Map<Instant, List<T>>> grouped = new LinkedHashMap<>();
        for (T sample : samples) {
            grouped.computeIfAbsent(key.apply(sample), k -> new TreeMap<>())
                    .computeIfAbsent(time.apply(sample), t -> new ArrayList<>())
                    .add(sample);
        }
        return grouped;
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> joined = new ArrayList<>(a);
        joined.addAll(b);
        return joined;
    }

    private static Key key(Long storeId, String host) {
        return new Key(storeId, host);
    }
}
