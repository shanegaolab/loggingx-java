package com.loggingx.web.monitor;

import com.loggingx.biz.monitor.MetricPoint;
import com.loggingx.biz.monitor.MonitorService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.web.log.LogIngestController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Receives host metrics from the collection agents: Vector {@code host_metrics} events converted to JSON
 * ({@code metric_to_log}), as a JSON array or one event per line, optionally gzip-compressed. Like log ingestion it
 * is authorized by the store's ingest token in the {@value LogIngestController#TOKEN_HEADER} header.
 */
@RestController
@RequestMapping("/metrics")
public class MetricIngestController {

    /** Larger requests are rejected; a scrape of a busy host is well below this. */
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int MAX_POINTS = 50_000;

    private final MonitorService monitorService;
    private final LogStoreRepository logStoreRepository;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public MetricIngestController(MonitorService monitorService, LogStoreRepository logStoreRepository) {
        this.monitorService = monitorService;
        this.logStoreRepository = logStoreRepository;
    }

    /**
     * @param accepted metric values read
     * @param samples  host samples stored
     */
    public record IngestResult(int accepted, int samples) {
    }

    @PostMapping("/ingest")
    public ApiResponse<IngestResult> ingest(
            @RequestHeader(value = LogIngestController.TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = HttpHeaders.CONTENT_ENCODING, required = false) String encoding,
            HttpServletRequest request) throws IOException {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException("Send the log store's ingest token in the "
                    + LogIngestController.TOKEN_HEADER + " header");
        }
        LogStore store = logStoreRepository.findByIngestToken(token.trim())
                .orElseThrow(() -> new UnauthorizedException("Ingest token is invalid"));
        if (!store.enabled()) {
            throw new ForbiddenException("Log store '" + store.code() + "' is disabled");
        }
        List<MetricPoint> points = parse(read(request.getInputStream(), encoding));
        return ApiResponse.success(new IngestResult(points.size(), monitorService.ingest(store.id(), points)));
    }

    private static byte[] read(InputStream body, String encoding) throws IOException {
        byte[] raw = body.readNBytes(MAX_BYTES + 1);
        if (raw.length > MAX_BYTES) {
            throw new BadRequestException("The request is larger than " + MAX_BYTES + " bytes");
        }
        if (encoding == null || !encoding.toLowerCase().contains("gzip")) {
            return raw;
        }
        try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] inflated = gzip.readNBytes(MAX_BYTES + 1);
            if (inflated.length > MAX_BYTES) {
                throw new BadRequestException("The request is larger than " + MAX_BYTES + " bytes uncompressed");
            }
            return inflated;
        } catch (IOException e) {
            throw new BadRequestException("The request body is not valid gzip");
        }
    }

    /**
     * Reads events such as {@code {"name":"cpu_seconds_total","host":"web-1","timestamp":"...",
     * "tags":{"cpu":"0","mode":"idle"},"counter":{"value":12.5}}}; events without a name, host, time or numeric value
     * are skipped.
     */
    List<MetricPoint> parse(byte[] body) {
        List<MetricPoint> points = new ArrayList<>();
        try (MappingIterator<JsonNode> values = JSON.readerFor(JsonNode.class).readValues(body)) {
            while (values.hasNext()) {
                JsonNode value = values.next();
                if (value.isArray()) {
                    value.forEach(event -> add(points, event));
                } else {
                    add(points, value);
                }
                if (points.size() > MAX_POINTS) {
                    throw new BadRequestException("At most " + MAX_POINTS + " metric values per request");
                }
            }
        } catch (JacksonException e) {
            throw new BadRequestException("The request body is not JSON metric events");
        }
        return points;
    }

    private static void add(List<MetricPoint> points, JsonNode event) {
        String name = text(event.path("name"));
        String host = text(event.path("host"));
        String timestamp = text(event.path("timestamp"));
        JsonNode value = event.has("gauge") ? event.path("gauge").path("value")
                : event.path("counter").path("value");
        if (name == null || host == null || timestamp == null || !value.isNumber()) {
            return;
        }
        Instant time;
        try {
            time = Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return;
        }
        Map<String, String> tags = new HashMap<>();
        event.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asString()));
        points.add(new MetricPoint(name, host, time, tags, value.asDouble()));
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() ? node.asString() : null;
    }
}
