package com.loggingx.web.bootstrap;

import com.loggingx.biz.log.LogEntry;
import com.loggingx.biz.log.LogIngestService;
import com.loggingx.biz.log.LogStoreCommand;
import com.loggingx.biz.log.LogStoreService;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds a demo log store under the {@code dev} profile while there are no stores, and fills it with sample logs from
 * the last half hour while it has none, so the log pages have something to show. With in-memory log storage the
 * samples come back on every start.
 */
@Component
@Profile("dev")
@Order(3)
public class DemoLogInitializer implements ApplicationRunner {

    static final String DEMO_STORE_CODE = "demo-app";

    private static final Logger log = LoggerFactory.getLogger(DemoLogInitializer.class);
    private static final String[] HOSTS = {"web-01", "web-02", "worker-01"};

    private final LogStoreService logStoreService;
    private final LogIngestService logIngestService;
    private final LogRecordRepository logRecordRepository;

    public DemoLogInitializer(LogStoreService logStoreService, LogIngestService logIngestService,
                              LogRecordRepository logRecordRepository) {
        this.logStoreService = logStoreService;
        this.logIngestService = logIngestService;
        this.logRecordRepository = logRecordRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (logStoreService.all().isEmpty()) {
            logStoreService.create(new LogStoreCommand(DEMO_STORE_CODE, "演示应用", "开发环境的示例日志库", 7, true),
                    "system");
            log.info("Seeded demo data: log store {}", DEMO_STORE_CODE);
        }
        LogStore store = logStoreService.all().stream()
                .filter(candidate -> DEMO_STORE_CODE.equals(candidate.code()))
                .findFirst()
                .orElse(null);
        if (store == null || !store.enabled()) {
            return;
        }
        try {
            if (logRecordRepository.lastLogTime(store.id()).isEmpty()) {
                logIngestService.ingestToStore(DEMO_STORE_CODE, sampleLogs());
                log.info("Seeded demo data: sample logs in {}", DEMO_STORE_CODE);
            }
        } catch (RuntimeException e) {
            log.warn("Cannot seed sample logs, the log storage is unavailable: {}", e.getMessage());
        }
    }

    private static List<LogEntry> sampleLogs() {
        Instant start = Instant.now().minusSeconds(30 * 60);
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String time = start.plusSeconds(i * 30L).toString();
            String host = HOSTS[i % HOSTS.length];
            String traceId = "trace-" + (1000 + i / 3);
            logs.add(switch (i % 10) {
                case 3 -> new LogEntry(time, "WARN", host, "com.demo.order.OrderService", traceId,
                        "Slow query: SELECT * FROM orders took " + (800 + i * 7) + " ms",
                        Map.of("durationMs", 800 + i * 7, "table", "orders"));
                case 7 -> new LogEntry(time, "ERROR", host, "com.demo.pay.PaymentClient", traceId,
                        "Payment failed for order " + (20_000 + i) + "\n"
                                + "java.net.SocketTimeoutException: Read timed out\n"
                                + "\tat com.demo.pay.PaymentClient.charge(PaymentClient.java:88)\n"
                                + "\tat com.demo.order.OrderService.pay(OrderService.java:142)",
                        Map.of("orderId", 20_000 + i, "retry", i % 3));
                case 9 -> new LogEntry(time, "DEBUG", host, "com.demo.cache.CacheManager", null,
                        "Cache refreshed: products (" + (100 + i) + " entries)", null);
                default -> new LogEntry(time, "INFO", host, "com.demo.web.AccessLog", traceId,
                        "GET /api/orders/" + (20_000 + i) + " 200 " + (12 + i % 40) + "ms",
                        Map.of("status", 200, "method", "GET"));
            });
        }
        return logs;
    }
}
