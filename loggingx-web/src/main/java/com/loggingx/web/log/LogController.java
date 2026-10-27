package com.loggingx.web.log;

import com.loggingx.biz.log.LogQueryService;
import com.loggingx.biz.log.LogTailService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.web.log.dto.LogRecordResponse;
import com.loggingx.web.log.dto.LogSearchRequest;
import com.loggingx.web.log.dto.LogSearchResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

/**
 * Searching and following logs.
 */
@RestController
@RequestMapping("/logs")
public class LogController {

    /**
     * A live view is closed after this long, and the client reconnects, so abandoned views do not linger.
     */
    static final Duration TAIL_TIMEOUT = Duration.ofMinutes(30);

    private final LogQueryService logQueryService;
    private final LogTailService logTailService;

    public LogController(LogQueryService logQueryService, LogTailService logTailService) {
        this.logQueryService = logQueryService;
        this.logTailService = logTailService;
    }

    @GetMapping("/search")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'LogQuery')")
    public ApiResponse<LogSearchResponse> search(LogSearchRequest request) {
        return ApiResponse.success(LogSearchResponse.from(logQueryService.search(request.toQuery())));
    }

    /**
     * Streams a store's logs as server-sent events: first its latest lines, then new ones as they arrive. Each
     * {@code logs} event holds a JSON array of logs; a {@code ready} event follows the latest lines, and comments are
     * sent as heartbeats while nothing arrives.
     */
    @GetMapping(value = "/tail", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'LogTail')")
    public SseEmitter tail(@RequestParam Long storeId,
                           @RequestParam(required = false) List<String> levels,
                           @RequestParam(required = false) String keyword) throws Exception {
        SseEmitter emitter = new SseEmitter(TAIL_TIMEOUT.toMillis());
        LogTailService.TailFilter filter = new LogTailService.TailFilter(
                levels == null ? null : new HashSet<>(levels), keyword);
        long subscription = logTailService.subscribe(storeId, filter, records -> {
            if (records.isEmpty()) {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } else {
                emitter.send(SseEmitter.event().name("logs")
                        .data(records.stream().map(LogRecordResponse::from).toList(), MediaType.APPLICATION_JSON));
            }
        });
        Runnable unsubscribe = () -> logTailService.unsubscribe(subscription);
        emitter.onCompletion(unsubscribe);
        emitter.onTimeout(unsubscribe);
        emitter.onError(error -> unsubscribe.run());
        emitter.send(SseEmitter.event().name("ready").data("ready"));
        return emitter;
    }
}
