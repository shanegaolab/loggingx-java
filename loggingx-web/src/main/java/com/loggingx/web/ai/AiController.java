package com.loggingx.web.ai;

import com.loggingx.ai.AiChatReply;
import com.loggingx.ai.AiChatService;
import com.loggingx.ai.AiChatStreamEvent;
import com.loggingx.ai.AiProvider;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadGatewayException;
import com.loggingx.web.ai.dto.AiChatRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final AiChatService aiChatService;

    public AiController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    /**
     * Providers that have an API key configured.
     */
    @GetMapping("/providers")
    public ApiResponse<List<String>> providers() {
        return ApiResponse.success(aiChatService.providers().stream().map(AiProvider::id).toList());
    }

    @PostMapping("/chat")
    public ApiResponse<AiChatReply> chat(@Valid @RequestBody AiChatRequest request) {
        return ApiResponse.success(aiChatService.chat(request.toCommand()));
    }

    /**
     * Streams the answer as server-sent events: {@code message} events carrying {@code {"content"}} pieces, then
     * one {@code done} event with the whole {@link AiChatReply}. Once the stream has started, a failure is sent as
     * an {@code error} event ({@code {"code","msg"}}, like the standard envelope) because the 200 status has already
     * been committed; invalid requests are rejected before that with the usual JSON response.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@Valid @RequestBody AiChatRequest request) {
        return aiChatService.stream(request.toCommand())
                .map(AiController::toEvent)
                .onErrorResume(exception -> Flux.just(errorEvent(exception)));
    }

    private static ServerSentEvent<Object> toEvent(AiChatStreamEvent event) {
        return switch (event) {
            case AiChatStreamEvent.Delta delta ->
                    ServerSentEvent.builder().event("message").data((Object) Map.of("content", delta.content())).build();
            case AiChatStreamEvent.Done done ->
                    ServerSentEvent.builder().event("done").data((Object) done.reply()).build();
        };
    }

    private static ServerSentEvent<Object> errorEvent(Throwable exception) {
        ApiResponse<Void> body;
        if (exception instanceof BadGatewayException badGateway) {
            body = ApiResponse.failure(HttpStatus.BAD_GATEWAY.value(), badGateway.getMessage());
        } else {
            log.error("Unexpected failure while streaming an AI answer", exception);
            body = ApiResponse.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred");
        }
        return ServerSentEvent.builder().event("error").data((Object) body).build();
    }
}
