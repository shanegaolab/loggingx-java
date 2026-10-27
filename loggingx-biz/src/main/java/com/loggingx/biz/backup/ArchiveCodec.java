package com.loggingx.biz.backup;

import com.loggingx.dao.log.LogRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The archive format: gzip-compressed JSON lines, one log per line, oldest first, readable without LoggingX
 * ({@code zcat 2026-10-20.ndjson.gz | jq .}):
 * <pre>
 * {"id":"…","time":"2026-10-20T08:00:00.123456Z","seq":0,"level":"ERROR","host":"web-1","source":"/var/log/app.log",
 *  "traceId":"…","message":"…","fields":{…},"receivedAt":"…"}
 * </pre>
 * Restoring keeps the ids, so a restored log is the same log.
 */
final class ArchiveCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ArchiveCodec() {
    }

    /**
     * @param records   logs written
     * @param rawBytes  size of the JSON lines before compression
     * @param sizeBytes size of the file
     * @param sha256    SHA-256 of the file, hex
     */
    record Written(long records, long rawBytes, long sizeBytes, String sha256) {
    }

    /**
     * A source of logs in pages; an empty page ends it.
     */
    interface Pages {
        List<LogRecord> next(LogRecord after);
    }

    static Written write(Path file, Pages pages) throws IOException {
        MessageDigest digest = sha256();
        long records = 0;
        CountingWriter counting;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(file), digest);
             GZIPOutputStream gzip = new GZIPOutputStream(out, 64 * 1024);
             Writer writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
            counting = new CountingWriter(writer);
            LogRecord last = null;
            List<LogRecord> page;
            while (!(page = pages.next(last)).isEmpty()) {
                for (LogRecord record : page) {
                    counting.line(line(record));
                    records++;
                }
                last = page.get(page.size() - 1);
            }
        }
        return new Written(records, counting.bytes, Files.size(file), HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Reads an archive, passing its logs to {@code batches} in batches of {@code batchSize}, filed under
     * {@code storeId}.
     *
     * @return the number of logs read
     */
    static long read(InputStream archive, Long storeId, int batchSize, Consumer<List<LogRecord>> batches)
            throws IOException {
        long count = 0;
        List<LogRecord> batch = new ArrayList<>(batchSize);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(archive, 64 * 1024),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                batch.add(record(line, storeId));
                count++;
                if (batch.size() == batchSize) {
                    batches.accept(batch);
                    batch = new ArrayList<>(batchSize);
                }
            }
        }
        if (!batch.isEmpty()) {
            batches.accept(batch);
        }
        return count;
    }

    static String line(LogRecord record) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", String.valueOf(record.id()));
        node.put("time", record.time().toString());
        node.put("seq", record.seq());
        node.put("level", record.level());
        putIfPresent(node, "host", record.host());
        putIfPresent(node, "source", record.source());
        putIfPresent(node, "traceId", record.traceId());
        node.put("message", record.message());
        if (record.fields() != null && !record.fields().isBlank()) {
            try {
                node.set("fields", JSON.readTree(record.fields()));
            } catch (JacksonException e) {
                node.put("fields", record.fields());
            }
        }
        if (record.receivedAt() != null) {
            node.put("receivedAt", record.receivedAt().toString());
        }
        return JSON.writeValueAsString(node);
    }

    static LogRecord record(String line, Long storeId) {
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not an archive line: "
                    + line.substring(0, Math.min(80, line.length())));
        }
        JsonNode fields = node.get("fields");
        String receivedAt = text(node, "receivedAt");
        return new LogRecord(Long.parseLong(text(node, "id")), storeId, Instant.parse(text(node, "time")),
                node.path("seq").asLong(0), text(node, "level"), text(node, "host"), text(node, "source"),
                text(node, "traceId"), text(node, "message"),
                fields == null || fields.isNull() ? null : fields.isString() ? fields.asString() : fields.toString(),
                receivedAt == null ? Instant.parse(text(node, "time")) : Instant.parse(receivedAt));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isEmpty()) {
            node.put(field, value);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes lines and counts their UTF-8 bytes.
     */
    private static final class CountingWriter {
        private final Writer writer;
        private long bytes;

        CountingWriter(Writer writer) {
            this.writer = writer;
        }

        void line(String text) {
            try {
                writer.write(text);
                writer.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bytes += text.getBytes(StandardCharsets.UTF_8).length + 1;
        }
    }
}
