-- Host resource metrics, applied by ClickHouseHostMetricRepository at startup; the database itself must already exist.
--
-- One row per host per scrape. Counters (cpu_*, disk_*, net_*) are cumulative, so rates come from consecutive rows; a
-- scrape that arrived in several requests has several rows with the same time, which reads merge with max(). Each row
-- carries the retention when it was written, so TTL drops expired rows during merges.
CREATE TABLE IF NOT EXISTS host_sample
(
    store_id         UInt64,
    host             LowCardinality(String),
    time             DateTime64(3, 'UTC'),
    cpu_total        Nullable(Float64),
    cpu_idle         Nullable(Float64),
    cpu_cores        Nullable(Float64),
    mem_total        Nullable(Float64),
    mem_used         Nullable(Float64),
    swap_total       Nullable(Float64),
    swap_used        Nullable(Float64),
    load1            Nullable(Float64),
    load5            Nullable(Float64),
    load15           Nullable(Float64),
    disk_read_bytes  Nullable(Float64),
    disk_write_bytes Nullable(Float64),
    net_rx_bytes     Nullable(Float64),
    net_tx_bytes     Nullable(Float64),
    uptime           Nullable(Float64),
    retention_days   UInt16
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (store_id, host, time)
TTL toDateTime(time) + toIntervalDay(retention_days);

-- Mounted file systems per scrape.
CREATE TABLE IF NOT EXISTS host_filesystem
(
    store_id       UInt64,
    host           LowCardinality(String),
    time           DateTime64(3, 'UTC'),
    mountpoint     String,
    fs_type        LowCardinality(String),
    total_bytes    Float64,
    used_bytes     Float64,
    retention_days UInt16
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (store_id, host, time, mountpoint)
TTL toDateTime(time) + toIntervalDay(retention_days);

-- Busy processes per scrape (agents report only processes above a CPU or memory threshold).
CREATE TABLE IF NOT EXISTS host_process
(
    store_id       UInt64,
    host           LowCardinality(String),
    time           DateTime64(3, 'UTC'),
    pid            UInt64,
    name           String,
    cpu_percent    Nullable(Float64),
    mem_bytes      Nullable(Float64),
    retention_days UInt16
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (store_id, host, time, pid)
TTL toDateTime(time) + toIntervalDay(retention_days);

-- Containers and services (control groups) per scrape; cpu_seconds is cumulative.
CREATE TABLE IF NOT EXISTS host_container
(
    store_id       UInt64,
    host           LowCardinality(String),
    time           DateTime64(3, 'UTC'),
    cgroup         String,
    cpu_seconds    Nullable(Float64),
    mem_bytes      Nullable(Float64),
    retention_days UInt16
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(time)
ORDER BY (store_id, host, time, cgroup)
TTL toDateTime(time) + toIntervalDay(retention_days)
