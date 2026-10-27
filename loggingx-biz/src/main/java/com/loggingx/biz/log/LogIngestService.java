package com.loggingx.biz.log;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.log.LogIds;
import com.loggingx.dao.log.LogLevel;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Validates log entries and stores them. HTTP producers authenticate with a store's ingest token; the Kafka consumer
 * is trusted and names the store by its code. Disabled stores accept nothing.
 * <p>
 * Entries are normalized rather than rejected: unknown levels become {@code INFO}, unparseable times the time of
 * receipt, and overlong values are truncated to fit their columns. Only entries without a message are dropped.
 * <p>
 * Order is kept: ids are assigned in the order of {@code entries}, so lines with the same time and no sequence number
 * stay in the order they were sent (see {@link LogRecord#ORDER}).
 */
@Service
public class LogIngestService {

    /**
     * Entries per HTTP request; larger batches should be split by the producer.
     */
    public static final int MAX_BATCH = 1000;

    // TEXT holds 65,535 bytes, and a character takes at most 4 bytes in utf8mb4.
    static final int MAX_MESSAGE_LENGTH = 16_000;
    static final int MAX_FIELDS_LENGTH = 16_000;
    private static final int MAX_HOST_LENGTH = 128;
    private static final int MAX_SOURCE_LENGTH = 255;
    private static final int MAX_TRACE_ID_LENGTH = 64;
    private static final Pattern EPOCH = Pattern.compile("\\d{9,13}(\\.\\d+)?");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LogStoreRepository logStoreRepository;
    private final LogRecordRepository logRecordRepository;

    public LogIngestService(LogStoreRepository logStoreRepository, LogRecordRepository logRecordRepository) {
        this.logStoreRepository = logStoreRepository;
        this.logRecordRepository = logRecordRepository;
    }

    /**
     * Stores entries sent over HTTP.
     *
     * @throws UnauthorizedException when no store has the token
     * @throws ForbiddenException    when the store is disabled
     * @throws BadRequestException   when there are more than {@link #MAX_BATCH} entries
     */
    public IngestResult ingestWithToken(String ingestToken, List<LogEntry> entries) {
        if (ingestToken == null || ingestToken.isBlank()) {
            throw new UnauthorizedException("Ingest token is required");
        }
        LogStore store = logStoreRepository.findByIngestToken(ingestToken.trim())
                .orElseThrow(() -> new UnauthorizedException("Ingest token is invalid"));
        if (entries.size() > MAX_BATCH) {
            throw new BadRequestException("At most " + MAX_BATCH + " logs can be sent per request");
        }
        return store(store, entries);
    }

    /**
     * Stores entries from a trusted channel, such as the Kafka consumer, addressed by store code.
     *
     * @throws NotFoundException  when no store has the code
     * @throws ForbiddenException when the store is disabled
     */
    public IngestResult ingestToStore(String storeCode, List<LogEntry> entries) {
        LogStore store = logStoreRepository.findByCode(storeCode == null ? "" : storeCode.trim())
                .orElseThrow(() -> new NotFoundException("Log store '" + storeCode + "' was not found"));
        return store(store, entries);
    }

    private IngestResult store(LogStore store, List<LogEntry> entries) {
        if (!store.enabled()) {
            throw new ForbiddenException("Log store '" + store.code() + "' is disabled");
        }
        Instant now = Instant.now();
        List<LogRecord> records = new ArrayList<>(entries.size());
        for (LogEntry entry : entries) {
            if (entry == null || entry.message() == null || entry.message().isBlank()) {
                continue;
            }
            long seq = entry.seq() == null || entry.seq() < 0 ? 0 : entry.seq();
            records.add(new LogRecord(LogIds.next(), store.id(), parseTime(entry.time()).orElse(now), seq,
                    LogLevel.parse(entry.level()).name(), truncate(entry.host(), MAX_HOST_LENGTH),
                    truncate(entry.source(), MAX_SOURCE_LENGTH), truncate(entry.traceId(), MAX_TRACE_ID_LENGTH),
                    limit(stripTrailingNewlines(entry.message()), MAX_MESSAGE_LENGTH), fieldsJson(entry.fields()),
                    now));
        }
        if (!records.isEmpty()) {
            // The storage expires rows a grace period after the retention; the daily purge deletes them on time, except
            // days held back until they are archived.
            logRecordRepository.insertAll(records, store.retentionDays() + LogRetentionService.TTL_GRACE_DAYS);
        }
        return new IngestResult(records.size(), entries.size() - records.size());
    }

    /**
     * Epoch values with up to 10 integer digits are seconds, longer ones milliseconds.
     */
    static Optional<Instant> parseTime(String time) {
        if (time == null || time.isBlank()) {
            return Optional.empty();
        }
        String value = time.trim();
        if (EPOCH.matcher(value).matches()) {
            int dot = value.indexOf('.');
            String integer = dot < 0 ? value : value.substring(0, dot);
            if (integer.length() > 10) {
                return Optional.of(Instant.ofEpochMilli(Long.parseLong(integer)));
            }
            double seconds = Double.parseDouble(value);
            return Optional.of(Instant.ofEpochMilli(Math.round(seconds * 1000)));
        }
        return DateTimes.parse(value);
    }

    private static String fieldsJson(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            String json = JSON.writeValueAsString(fields);
            return json.length() <= MAX_FIELDS_LENGTH ? json : "{\"_truncated\":true}";
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String stripTrailingNewlines(String message) {
        int end = message.length();
        while (end > 0 && (message.charAt(end - 1) == '\n' || message.charAt(end - 1) == '\r')) {
            end--;
        }
        return message.substring(0, end);
    }

    private static String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
