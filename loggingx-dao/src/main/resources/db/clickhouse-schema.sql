-- Log lines, applied by ClickHouseLogRecordRepository at startup; the database itself must already exist.
--
-- Rows are sorted by store and time, which every search filters on, and partitioned by day. Lines with the same time
-- are sorted by the producer's sequence number (seq, e.g. the byte offset in the log file; 0 when not given) and then
-- by id, the order they were received in, so a search returns them in their original order (see LogRecord.ORDER).
-- Each row carries its store's retention when it was written, so TTL drops expired rows during merges;
-- LogRetentionService additionally applies retention changes and removes the rows of deleted stores. Ids are
-- time-ordered (see LogIds), and the minmax index lets the live view read only the newest granules. Empty strings
-- stand for missing optional values.
CREATE TABLE IF NOT EXISTS log_record
(
    id             UInt64,
    store_id       UInt64,
    log_time       DateTime64(6, 'UTC'),
    seq            UInt64,
    level          LowCardinality(String),
    host           LowCardinality(String),
    source         String,
    trace_id       String,
    message        String CODEC(ZSTD(3)),
    fields         String CODEC(ZSTD(3)),
    received_at    DateTime64(6, 'UTC'),
    retention_days UInt16,
    INDEX idx_id id TYPE minmax GRANULARITY 1,
    INDEX idx_trace_id trace_id TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(log_time)
ORDER BY (store_id, log_time, seq, id)
TTL toDateTime(log_time) + toIntervalDay(retention_days)
