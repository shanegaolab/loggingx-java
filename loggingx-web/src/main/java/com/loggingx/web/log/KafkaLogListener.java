package com.loggingx.web.log;

import com.loggingx.biz.log.LogEntry;
import com.loggingx.biz.log.LogIngestService;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes logs that collector agents publish to Kafka ({@code loggingx.log.kafka.*}). Each message value is a payload
 * as described in {@link LogPayloads}. The target store is named by code: by the payload's {@code logstore} property,
 * else by a {@value #STORE_HEADER} header, else by the message key. Messages are read in batches and stored per store.
 * <p>
 * Order: Kafka keeps order only within a partition, and this listener keeps it too, since it stores a partition's
 * messages in offset order (ids follow it, see {@link com.loggingx.dao.log.LogRecord#ORDER}). Producers should
 * therefore send all lines of one source (one log file of one host) to the same partition, e.g. with the host and file
 * as the message key and the store in the header or payload. Lines with the same time from different partitions are
 * ordered by their sequence number ({@code seq}, or Filebeat's {@code log.offset}) when the producer sends one.
 * <p>
 * Malformed messages and messages for unknown or disabled stores are logged and skipped, so they cannot block the
 * partition. Storage failures propagate, and the listener container retries the batch.
 */
@Component
@ConditionalOnProperty(name = "loggingx.log.kafka.enabled", havingValue = "true")
public class KafkaLogListener {

    public static final String STORE_HEADER = "logstore";

    private static final Logger log = LoggerFactory.getLogger(KafkaLogListener.class);

    private final LogIngestService logIngestService;

    public KafkaLogListener(LogIngestService logIngestService) {
        this.logIngestService = logIngestService;
    }

    @KafkaListener(
            id = "loggingx-logs",
            topics = "#{'${loggingx.log.kafka.topics}'.split(',')}",
            groupId = "${loggingx.log.kafka.group-id}",
            batch = "true")
    public void onMessages(List<ConsumerRecord<String, String>> messages) {
        Map<String, List<LogEntry>> byStore = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> message : messages) {
            if (message.value() == null) {
                continue;
            }
            LogPayloads.Batch batch;
            try {
                batch = LogPayloads.parse(message.value());
            } catch (IllegalArgumentException e) {
                log.warn("Skipping malformed log message at {}-{}@{}: {}", message.topic(), message.partition(),
                        message.offset(), e.getMessage());
                continue;
            }
            String storeCode = batch.storeCode() != null ? batch.storeCode() : storeHeader(message);
            if (storeCode == null) {
                storeCode = message.key();
            }
            if (storeCode == null || storeCode.isBlank()) {
                log.warn("Skipping log message at {}-{}@{} that names no log store", message.topic(),
                        message.partition(), message.offset());
                continue;
            }
            byStore.computeIfAbsent(storeCode.trim(), code -> new ArrayList<>()).addAll(batch.entries());
        }
        byStore.forEach((storeCode, entries) -> {
            try {
                logIngestService.ingestToStore(storeCode, entries);
            } catch (NotFoundException | ForbiddenException e) {
                log.warn("Skipping {} logs from Kafka: {}", entries.size(), e.getMessage());
            }
        });
    }

    private static String storeHeader(ConsumerRecord<String, String> message) {
        Header header = message.headers().lastHeader(STORE_HEADER);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
