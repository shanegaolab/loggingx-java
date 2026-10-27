package com.loggingx.dao.log;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stores log lines in ClickHouse ({@code loggingx.log.storage=clickhouse}, the default), in the {@code log_record}
 * table of {@code db/clickhouse-schema.sql}.
 * <p>
 * It uses its own connection pool rather than a {@code DataSource} bean, so the MySQL data source stays the one
 * Spring Boot configures. The application starts even when ClickHouse is unreachable: the schema is applied when
 * possible at startup, and log reads and writes fail until ClickHouse is back.
 * <p>
 * Times are exchanged as epoch microseconds, which is exact for {@code DateTime64(6)} and independent of time zones:
 * inserted integers are read by ClickHouse as microseconds, and filters convert with {@code fromUnixTimestamp64Micro}.
 */
@Repository
@ConditionalOnProperty(name = "loggingx.log.storage", havingValue = "clickhouse", matchIfMissing = true)
public class ClickHouseLogRecordRepository implements LogRecordRepository, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseLogRecordRepository.class);
    private static final String COLUMNS = "id, store_id, toUnixTimestamp64Micro(log_time) AS log_time_us, seq, level, "
            + "host, source, trace_id, message, fields, toUnixTimestamp64Micro(received_at) AS received_at_us";
    private static final RowMapper<LogRecord> RECORD = ClickHouseLogRecordRepository::mapRecord;

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public ClickHouseLogRecordRepository(
            @Value("${loggingx.clickhouse.url}") String url,
            @Value("${loggingx.clickhouse.username:default}") String username,
            @Value("${loggingx.clickhouse.password:}") String password,
            @Value("${loggingx.clickhouse.pool-size:5}") int poolSize
    ) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("loggingx-clickhouse");
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        // Open connections on demand only, so an unreachable ClickHouse is not retried in the background.
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        // Start without a connection, so an unreachable ClickHouse does not stop the application.
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        applySchema();
    }

    private void applySchema() {
        try {
            String ddl = new ClassPathResource("db/clickhouse-schema.sql").getContentAsString(StandardCharsets.UTF_8);
            jdbc.execute(stripComments(ddl));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log.warn("Cannot apply the ClickHouse log schema; log storage is unavailable until ClickHouse is reachable "
                    + "and the schema is applied (restart the application): {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    @Override
    public void insertAll(List<LogRecord> records, int retentionDays) {
        if (records.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO log_record (id, store_id, log_time, seq, level, host, source, trace_id, message, "
                        + "fields, received_at, retention_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                records, records.size(), (statement, record) -> {
                    statement.setLong(1, record.id());
                    statement.setLong(2, record.storeId());
                    statement.setLong(3, micros(record.time()));
                    statement.setLong(4, record.seq());
                    statement.setString(5, record.level());
                    statement.setString(6, emptyIfNull(record.host()));
                    statement.setString(7, emptyIfNull(record.source()));
                    statement.setString(8, emptyIfNull(record.traceId()));
                    statement.setString(9, record.message());
                    statement.setString(10, emptyIfNull(record.fields()));
                    statement.setLong(11, micros(record.receivedAt()));
                    statement.setInt(12, retentionDays);
                });
    }

    @Override
    public List<LogRecord> search(LogSearch search, long offset, int limit) {
        Where where = where(search);
        List<Object> args = new ArrayList<>(where.args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT " + COLUMNS + " FROM log_record " + where.sql
                + " ORDER BY log_time DESC, seq DESC, id DESC LIMIT ? OFFSET ?", RECORD, args.toArray());
    }

    @Override
    public long count(LogSearch search) {
        Where where = where(search);
        Long count = jdbc.queryForObject("SELECT count() FROM log_record " + where.sql, Long.class,
                where.args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<LevelCount> countByLevel(LogSearch search) {
        Where where = where(search);
        return jdbc.query("SELECT level, count() AS record_count FROM log_record " + where.sql + " GROUP BY level",
                (rs, row) -> new LevelCount(rs.getString("level"), rs.getLong("record_count")), where.args.toArray());
    }

    @Override
    public List<LevelBucket> levelHistogram(Instant from, Instant to, Duration bucket) {
        long size = bucket.toNanos() / 1000;
        return jdbc.query("SELECT intDiv(toUnixTimestamp64Micro(log_time), ?) AS bucket, store_id, level, "
                        + "count() AS record_count FROM log_record WHERE log_time >= fromUnixTimestamp64Micro(?) "
                        + "AND log_time < fromUnixTimestamp64Micro(?) GROUP BY bucket, store_id, level",
                (rs, row) -> new LevelBucket(instant(rs.getLong("bucket") * size), rs.getLong("store_id"),
                        rs.getString("level"), rs.getLong("record_count")),
                size, micros(from), micros(to));
    }

    @Override
    public List<LogRecord> findAfter(Long storeId, long afterId, long upToId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM log_record WHERE store_id = ? AND id > ? AND id <= ? "
                + "ORDER BY id LIMIT ?", RECORD, storeId, afterId, upToId, limit);
    }

    @Override
    public List<LogRecord> findLatest(Long storeId, int limit) {
        List<LogRecord> newestFirst = new ArrayList<>(jdbc.query("SELECT " + COLUMNS + " FROM log_record "
                + "WHERE store_id = ? ORDER BY log_time DESC, seq DESC, id DESC LIMIT ?", RECORD, storeId, limit));
        return newestFirst.reversed();
    }

    @Override
    public Optional<Instant> lastLogTime(Long storeId) {
        // max() of an empty set is the epoch rather than NULL in ClickHouse, so read the newest row instead.
        return jdbc.query("SELECT toUnixTimestamp64Micro(log_time) FROM log_record WHERE store_id = ? "
                        + "ORDER BY log_time DESC LIMIT 1", (rs, row) -> instant(rs.getLong(1)), storeId)
                .stream().findFirst();
    }

    @Override
    public long countSince(Long storeId, Instant since) {
        Long count = jdbc.queryForObject("SELECT count() FROM log_record WHERE store_id = ? "
                + "AND log_time >= fromUnixTimestamp64Micro(?)", Long.class, storeId, micros(since));
        return count == null ? 0 : count;
    }

    @Override
    public void deleteBefore(Long storeId, Instant before) {
        // Lightweight delete: rows disappear from queries at once and are removed by later merges.
        jdbc.update("DELETE FROM log_record WHERE store_id = ? AND log_time < fromUnixTimestamp64Micro(?)",
                storeId, micros(before));
    }

    @Override
    public void deleteBefore(Long storeId, Instant before, List<TimeRange> keep) {
        StringBuilder sql = new StringBuilder(
                "DELETE FROM log_record WHERE store_id = ? AND log_time < fromUnixTimestamp64Micro(?)");
        List<Object> args = new ArrayList<>(List.of(storeId, micros(before)));
        for (TimeRange range : keep) {
            sql.append(" AND NOT (log_time >= fromUnixTimestamp64Micro(?) AND log_time < fromUnixTimestamp64Micro(?))");
            args.add(micros(range.start()));
            args.add(micros(range.end()));
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public void deleteBetween(Long storeId, TimeRange range) {
        jdbc.update("DELETE FROM log_record WHERE store_id = ? AND log_time >= fromUnixTimestamp64Micro(?) "
                + "AND log_time < fromUnixTimestamp64Micro(?)", storeId, micros(range.start()), micros(range.end()));
    }

    @Override
    public List<LogRecord> scan(Long storeId, Instant start, Instant end, LogRecord after, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM log_record WHERE store_id = ? "
                + "AND log_time >= fromUnixTimestamp64Micro(?) AND log_time < fromUnixTimestamp64Micro(?)");
        List<Object> args = new ArrayList<>(List.of(storeId, micros(start), micros(end)));
        if (after != null) {
            // Rows after the previous page in sort-key order, which follows the table's ORDER BY.
            sql.append(" AND (log_time, seq, id) > (fromUnixTimestamp64Micro(?), ?, ?)");
            args.add(micros(after.time()));
            args.add(after.seq());
            args.add(after.id());
        }
        sql.append(" ORDER BY log_time, seq, id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), RECORD, args.toArray());
    }

    @Override
    public void deleteByStore(Long storeId) {
        jdbc.update("DELETE FROM log_record WHERE store_id = ?", storeId);
    }

    @Override
    public Set<Long> storeIds() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT store_id FROM log_record", Long.class));
    }

    private record Where(String sql, List<Object> args) {
    }

    private static Where where(LogSearch search) {
        StringBuilder sql = new StringBuilder("WHERE store_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(search.storeId());
        if (search.start() != null) {
            sql.append(" AND log_time >= fromUnixTimestamp64Micro(?)");
            args.add(micros(search.start()));
        }
        if (search.end() != null) {
            sql.append(" AND log_time < fromUnixTimestamp64Micro(?)");
            args.add(micros(search.end()));
        }
        if (!search.levels().isEmpty()) {
            sql.append(" AND level IN (").append(String.join(", ", search.levels().stream().map(l -> "?").toList()))
                    .append(')');
            args.addAll(search.levels());
        }
        if (search.host() != null) {
            sql.append(" AND host = ?");
            args.add(search.host());
        }
        if (search.traceId() != null) {
            sql.append(" AND trace_id = ?");
            args.add(search.traceId());
        }
        if (search.keyword() != null && !search.keyword().isEmpty()) {
            sql.append(" AND message ILIKE ?");
            args.add(likePattern(search.keyword()));
        }
        return new Where(sql.toString(), args);
    }

    /**
     * @return a pattern matching the keyword anywhere, with ClickHouse's {@code LIKE} wildcards escaped
     */
    static String likePattern(String keyword) {
        String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static LogRecord mapRecord(ResultSet rs, int row) throws SQLException {
        return new LogRecord(rs.getLong("id"), rs.getLong("store_id"), instant(rs.getLong("log_time_us")),
                rs.getLong("seq"), rs.getString("level"), nullIfEmpty(rs.getString("host")),
                nullIfEmpty(rs.getString("source")),
                nullIfEmpty(rs.getString("trace_id")), rs.getString("message"), nullIfEmpty(rs.getString("fields")),
                instant(rs.getLong("received_at_us")));
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String stripComments(String sql) {
        return sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n").trim();
    }
}
