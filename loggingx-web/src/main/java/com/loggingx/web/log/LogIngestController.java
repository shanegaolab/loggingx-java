package com.loggingx.web.log;

import com.loggingx.biz.log.IngestResult;
import com.loggingx.biz.log.LogIngestService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives logs from producers over HTTP. It is not behind user login: the {@value #TOKEN_HEADER} header carries the
 * target store's ingest token instead, and the store is the token's, whatever {@code logstore} the payload names.
 * The payload format is described in {@link LogPayloads}, as JSON or as newline-delimited JSON (one log per line, as
 * Fluent Bit's {@code json_lines} sends); at most {@link LogIngestService#MAX_BATCH} logs per request.
 * <p>
 * With {@value #DEDUP_HEADER}{@code : batch} (the LoggingX agent sends it), a request repeating a batch stored within
 * the last half hour - an agent retrying after a lost answer - is acknowledged without storing it again; see
 * {@link IngestDeduplicator}. Without it, every request is stored, so a client may send the same lines twice on
 * purpose.
 */
@RestController
@RequestMapping("/logs")
public class LogIngestController {

    public static final String TOKEN_HEADER = "X-Log-Token";
    public static final String DEDUP_HEADER = "X-Log-Dedup";

    private final LogIngestService logIngestService;
    private final IngestDeduplicator deduplicator;

    public LogIngestController(LogIngestService logIngestService, IngestDeduplicator deduplicator) {
        this.logIngestService = logIngestService;
        this.deduplicator = deduplicator;
    }

    // Read as text, whatever the content type, so JSON and newline-delimited JSON are both accepted.
    @PostMapping("/ingest")
    public ApiResponse<IngestResult> ingest(@RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                            @RequestHeader(value = DEDUP_HEADER, required = false) String dedup,
                                            @RequestBody String body) {
        LogPayloads.Batch batch;
        try {
            batch = LogPayloads.parse(body);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        if (!"batch".equalsIgnoreCase(dedup == null ? "" : dedup.trim())) {
            return ApiResponse.success(logIngestService.ingestWithToken(token, batch.entries()));
        }
        String key = IngestDeduplicator.key(token, body);
        if (deduplicator.seen(key)) {
            return ApiResponse.success(new IngestResult(0, 0, batch.entries().size()));
        }
        IngestResult result = logIngestService.ingestWithToken(token, batch.entries());
        // Remembered only once stored: a request that failed is stored when it is retried.
        deduplicator.remember(key);
        return ApiResponse.success(result);
    }
}
