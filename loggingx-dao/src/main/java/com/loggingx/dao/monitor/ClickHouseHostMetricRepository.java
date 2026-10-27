package com.loggingx.dao.monitor;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Stores host metrics in ClickHouse ({@code loggingx.log.storage=clickhouse}), in the tables of
 * {@code db/clickhouse-monitor-schema.sql}. Like the log storage it has its own small connection pool and starts
 * without ClickHouse. Times are exchanged as epoch milliseconds, the precision of the {@code DateTime64(3)} columns.
 */
@Repository
@ConditionalOnProperty(name = "loggingx.log.storage", havingValue = "clickhouse", matchIfMissing = true)
public class ClickHouseHostMetricRepository implements HostMetricRepository, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseHostMetricRepository.class);
    private static final String SAMPLE_VALUES = "max(cpu_total) AS cpu_total, max(cpu_idle) AS cpu_idle, "
            + "max(cpu_cores) AS cpu_cores, max(mem_total) AS mem_total, %1$s(mem_used) AS mem_used, "
            + "max(swap_total) AS swap_total, %1$s(swap_used) AS swap_used, %1$s(load1) AS load1, "
            + "%1$s(load5) AS load5, %1$s(load15) AS load15, max(disk_read_bytes) AS disk_read_bytes, "
            + "max(disk_write_bytes) AS disk_write_bytes, max(net_rx_bytes) AS net_rx_bytes, "
            + "max(net_tx_bytes) AS net_tx_bytes, max(uptime) AS uptime";
    private static final RowMapper<HostSample> SAMPLE = ClickHouseHostMetricRepository::mapSample;

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public ClickHouseHostMetricRepository(
            @Value("${loggingx.clickhouse.url}") String url,
            @Value("${loggingx.clickhouse.username:default}") String username,
            @Value("${loggingx.clickhouse.password:}") String password
    ) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("loggingx-clickhouse-monitor");
        config.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        applySchema();
    }

    private void applySchema() {
        try {
            String ddl = new ClassPathResource("db/clickhouse-monitor-schema.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            String sql = ddl.lines().filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + b + "\n");
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) {
                    jdbc.execute(statement.trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log.warn("Cannot apply the ClickHouse monitor schema; host metrics are unavailable until ClickHouse is "
                    + "reachable and the schema is applied (restart the application): {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        dataSource.close();
    }

    @Override
    public void insert(List<HostSample> hosts, List<FilesystemSample> filesystems, List<ProcessSample> processes,
                       List<ContainerSample> containers, int retentionDays) {
        if (!hosts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO host_sample (store_id, host, time, cpu_total, cpu_idle, cpu_cores, "
                            + "mem_total, mem_used, swap_total, swap_used, load1, load5, load15, disk_read_bytes, "
                            + "disk_write_bytes, net_rx_bytes, net_tx_bytes, uptime, retention_days) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    hosts, hosts.size(), (statement, sample) -> {
                        statement.setLong(1, sample.storeId());
                        statement.setString(2, sample.host());
                        statement.setLong(3, sample.time().toEpochMilli());
                        Double[] values = {sample.cpuTotal(), sample.cpuIdle(), sample.cpuCores(), sample.memTotal(),
                                sample.memUsed(), sample.swapTotal(), sample.swapUsed(), sample.load1(),
                                sample.load5(), sample.load15(), sample.diskReadBytes(), sample.diskWriteBytes(),
                                sample.netRxBytes(), sample.netTxBytes(), sample.uptime()};
                        for (int i = 0; i < values.length; i++) {
                            setDouble(statement, 4 + i, values[i]);
                        }
                        statement.setInt(19, retentionDays);
                    });
        }
        if (!filesystems.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO host_filesystem (store_id, host, time, mountpoint, fs_type, total_bytes, "
                            + "used_bytes, retention_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    filesystems, filesystems.size(), (statement, sample) -> {
                        statement.setLong(1, sample.storeId());
                        statement.setString(2, sample.host());
                        statement.setLong(3, sample.time().toEpochMilli());
                        statement.setString(4, sample.mountpoint());
                        statement.setString(5, sample.fsType() == null ? "" : sample.fsType());
                        statement.setDouble(6, sample.totalBytes());
                        statement.setDouble(7, sample.usedBytes());
                        statement.setInt(8, retentionDays);
                    });
        }
        if (!processes.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO host_process (store_id, host, time, pid, name, cpu_percent, mem_bytes, "
                            + "retention_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    processes, processes.size(), (statement, sample) -> {
                        statement.setLong(1, sample.storeId());
                        statement.setString(2, sample.host());
                        statement.setLong(3, sample.time().toEpochMilli());
                        statement.setLong(4, sample.pid());
                        statement.setString(5, sample.name());
                        setDouble(statement, 6, sample.cpuPercent());
                        setDouble(statement, 7, sample.memBytes());
                        statement.setInt(8, retentionDays);
                    });
        }
        if (!containers.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO host_container (store_id, host, time, cgroup, cpu_seconds, mem_bytes, "
                            + "retention_days) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    containers, containers.size(), (statement, sample) -> {
                        statement.setLong(1, sample.storeId());
                        statement.setString(2, sample.host());
                        statement.setLong(3, sample.time().toEpochMilli());
                        statement.setString(4, sample.cgroup());
                        setDouble(statement, 5, sample.cpuSeconds());
                        setDouble(statement, 6, sample.memBytes());
                        statement.setInt(7, retentionDays);
                    });
        }
    }

    @Override
    public List<HostSample> latestPairs(Instant since) {
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(time) AS time_ms, "
                + SAMPLE_VALUES.formatted("max") + " FROM host_sample WHERE time >= fromUnixTimestamp64Milli(?) "
                + "GROUP BY store_id, host, time ORDER BY store_id, host, time DESC LIMIT 2 BY store_id, host",
                SAMPLE, since.toEpochMilli());
    }

    @Override
    public List<HostSample> samplesSince(Instant since) {
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(time) AS time_ms, "
                + SAMPLE_VALUES.formatted("max") + " FROM host_sample WHERE time >= fromUnixTimestamp64Milli(?) "
                + "GROUP BY store_id, host, time ORDER BY store_id, host, time", SAMPLE, since.toEpochMilli());
    }

    @Override
    public List<HostSample> series(Long storeId, String host, Instant from, Instant to, Duration bucket) {
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(max(time)) AS time_ms, "
                        + SAMPLE_VALUES.formatted("avg") + ", intDiv(toUnixTimestamp64Milli(time), ?) AS bucket "
                        + "FROM host_sample WHERE store_id = ? AND host = ? "
                        + "AND time >= fromUnixTimestamp64Milli(?) AND time < fromUnixTimestamp64Milli(?) "
                        + "GROUP BY store_id, host, bucket ORDER BY bucket",
                SAMPLE, bucket.toMillis(), storeId, host, from.toEpochMilli(), to.toEpochMilli());
    }

    @Override
    public List<FilesystemUsage> filesystemSeries(Long storeId, String host, Instant from, Instant to,
                                                  Duration bucket) {
        long bucketMs = bucket.toMillis();
        return jdbc.query("SELECT intDiv(toUnixTimestamp64Milli(time), ?) AS bucket, "
                        + "max(if(total_bytes > 0, used_bytes * 100 / total_bytes, 0)) AS used_percent "
                        + "FROM host_filesystem WHERE store_id = ? AND host = ? "
                        + "AND time >= fromUnixTimestamp64Milli(?) AND time < fromUnixTimestamp64Milli(?) "
                        + "GROUP BY bucket ORDER BY bucket",
                (rs, row) -> new FilesystemUsage(Instant.ofEpochMilli(rs.getLong("bucket") * bucketMs),
                        rs.getDouble("used_percent")),
                bucketMs, storeId, host, from.toEpochMilli(), to.toEpochMilli());
    }

    @Override
    public List<FilesystemSample> latestFilesystems(Instant since) {
        long sinceMs = since.toEpochMilli();
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(time) AS time_ms, mountpoint, fs_type, "
                        + "total_bytes, used_bytes FROM host_filesystem WHERE time >= fromUnixTimestamp64Milli(?) "
                        + "AND (store_id, host, time) IN (SELECT store_id, host, max(time) FROM host_filesystem "
                        + "WHERE time >= fromUnixTimestamp64Milli(?) GROUP BY store_id, host)",
                (rs, row) -> new FilesystemSample(rs.getLong("store_id"), rs.getString("host"),
                        Instant.ofEpochMilli(rs.getLong("time_ms")), rs.getString("mountpoint"),
                        rs.getString("fs_type"), rs.getDouble("total_bytes"), rs.getDouble("used_bytes")),
                sinceMs, sinceMs);
    }

    @Override
    public List<ProcessSample> processesAt(Long storeId, String host, Instant at, Duration maxAge) {
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(time) AS time_ms, pid, name, cpu_percent, "
                        + "mem_bytes FROM host_process WHERE store_id = ? AND host = ? AND time = ("
                        + "SELECT max(time) FROM host_process WHERE store_id = ? AND host = ? "
                        + "AND time <= fromUnixTimestamp64Milli(?) AND time >= fromUnixTimestamp64Milli(?))",
                (rs, row) -> new ProcessSample(rs.getLong("store_id"), rs.getString("host"),
                        Instant.ofEpochMilli(rs.getLong("time_ms")), rs.getLong("pid"), rs.getString("name"),
                        nullableDouble(rs, "cpu_percent"), nullableDouble(rs, "mem_bytes")),
                storeId, host, storeId, host, at.toEpochMilli(), at.minus(maxAge).toEpochMilli());
    }

    @Override
    public List<ContainerSample> latestContainers(Long storeId, String host, Instant since) {
        return jdbc.query("SELECT store_id, host, toUnixTimestamp64Milli(time) AS time_ms, cgroup, cpu_seconds, "
                        + "mem_bytes FROM host_container WHERE store_id = ? AND host = ? AND time IN ("
                        + "SELECT DISTINCT time FROM host_container WHERE store_id = ? AND host = ? "
                        + "AND time >= fromUnixTimestamp64Milli(?) ORDER BY time DESC LIMIT 2) "
                        + "ORDER BY time DESC",
                (rs, row) -> new ContainerSample(rs.getLong("store_id"), rs.getString("host"),
                        Instant.ofEpochMilli(rs.getLong("time_ms")), rs.getString("cgroup"),
                        nullableDouble(rs, "cpu_seconds"), nullableDouble(rs, "mem_bytes")),
                storeId, host, storeId, host, since.toEpochMilli());
    }

    @Override
    public void deleteByStore(Long storeId) {
        for (String table : List.of("host_sample", "host_filesystem", "host_process", "host_container")) {
            jdbc.update("DELETE FROM " + table + " WHERE store_id = ?", storeId);
        }
    }

    private static HostSample mapSample(ResultSet rs, int row) throws SQLException {
        return new HostSample(rs.getLong("store_id"), rs.getString("host"),
                Instant.ofEpochMilli(rs.getLong("time_ms")), nullableDouble(rs, "cpu_total"),
                nullableDouble(rs, "cpu_idle"), nullableDouble(rs, "cpu_cores"), nullableDouble(rs, "mem_total"),
                nullableDouble(rs, "mem_used"), nullableDouble(rs, "swap_total"), nullableDouble(rs, "swap_used"),
                nullableDouble(rs, "load1"), nullableDouble(rs, "load5"), nullableDouble(rs, "load15"),
                nullableDouble(rs, "disk_read_bytes"), nullableDouble(rs, "disk_write_bytes"),
                nullableDouble(rs, "net_rx_bytes"), nullableDouble(rs, "net_tx_bytes"), nullableDouble(rs, "uptime"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() || Double.isNaN(value) ? null : value;
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }
}
