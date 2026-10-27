package com.loggingx.dao.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Host resource metrics. The implementation is chosen like the log storage, by {@code loggingx.log.storage}:
 * ClickHouse, or memory for local runs. Hosts are identified by store and lower-case host name.
 */
public interface HostMetricRepository {

    /**
     * @param retentionDays samples older than this many days are dropped
     */
    void insert(List<HostSample> hosts, List<FilesystemSample> filesystems, List<ProcessSample> processes,
                List<ContainerSample> containers, int retentionDays);

    /**
     * @return for every host with samples since {@code since}, its latest two samples (newest first), merged per
     * time; rates come from the pair
     */
    List<HostSample> latestPairs(Instant since);

    /**
     * @return every host's samples since {@code since}, merged per time, in time order per host
     */
    List<HostSample> samplesSince(Instant since);

    /**
     * Samples of one host, one per bucket: counters at their largest value in the bucket, gauges averaged, and the
     * time of the bucket's last sample.
     *
     * @param from inclusive
     * @param to   exclusive
     * @return in time order
     */
    List<HostSample> series(Long storeId, String host, Instant from, Instant to, Duration bucket);

    /**
     * @return the largest file system use of one host per bucket, in time order
     */
    List<FilesystemUsage> filesystemSeries(Long storeId, String host, Instant from, Instant to, Duration bucket);

    /**
     * @return for every host with file system samples since {@code since}, the file systems of its latest scrape
     */
    List<FilesystemSample> latestFilesystems(Instant since);

    /**
     * @return the processes of the host's latest scrape at or before {@code at}, no older than {@code maxAge}
     */
    List<ProcessSample> processesAt(Long storeId, String host, Instant at, Duration maxAge);

    /**
     * @return the containers of the host's latest two scrapes since {@code since}, newest first
     */
    List<ContainerSample> latestContainers(Long storeId, String host, Instant since);

    /**
     * Deletes the metrics of a deleted store.
     */
    void deleteByStore(Long storeId);
}
