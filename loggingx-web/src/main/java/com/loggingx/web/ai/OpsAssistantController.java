package com.loggingx.web.ai;

import com.loggingx.ai.AiChatStreamEvent;
import com.loggingx.biz.ai.OpsAssistantService;
import com.loggingx.biz.ai.OpsAssistantService.AskEvent;
import com.loggingx.biz.ai.OpsAssistantService.Source;
import com.loggingx.biz.ai.OpsAssistantService.Turn;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadGatewayException;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.ai.AiKnowledge;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The operations assistant (questions answered from LoggingX's own data) and its knowledge base: the assistant
 * page asks, the knowledge page edits. Material is limited to the stores the user may access.
 */
@RestController
@RequestMapping("/ai-ops")
@PreAuthorize(AiDiagnosisController.ANY_AI_PAGE)
public class OpsAssistantController {

    private static final String CHAT = "@permissionService.hasAnyMenu(authentication, 'AiChat')";
    private static final String KNOWLEDGE = "@permissionService.hasAnyMenu(authentication, 'AiKnowledge')";
    /** A document is also opened from the sources of an answer. */
    private static final String KNOWLEDGE_READERS =
            "@permissionService.hasAnyMenu(authentication, 'AiKnowledge', 'AiChat')";

    private static final Logger log = LoggerFactory.getLogger(OpsAssistantController.class);

    private final OpsAssistantService service;
    private final StoreAccessService storeAccessService;
    private final LogStoreRepository logStoreRepository;

    public OpsAssistantController(OpsAssistantService service, StoreAccessService storeAccessService,
                                  LogStoreRepository logStoreRepository) {
        this.service = service;
        this.storeAccessService = storeAccessService;
        this.logStoreRepository = logStoreRepository;
    }

    // ---------- knowledge base ----------

    public record KnowledgeItem(Long id, String title, String excerpt, List<String> tags, Long storeId,
                                String storeName, int length, String createdBy, String updateTime) {
    }

    public record KnowledgeResponse(KnowledgeItem item, String content) {
    }

    public record KnowledgeRequest(String title, String content, String tags, Long storeId) {
    }

    @GetMapping("/knowledge")
    @PreAuthorize(KNOWLEDGE)
    public ApiResponse<List<KnowledgeItem>> knowledge(Authentication authentication) {
        Map<Long, String> stores = storeNames();
        return ApiResponse.success(service.knowledge(storeAccessService.scopeOf(authentication.getName())).stream()
                .map(item -> item(item, stores)).toList());
    }

    @GetMapping("/knowledge/{id}")
    @PreAuthorize(KNOWLEDGE_READERS)
    public ApiResponse<KnowledgeResponse> knowledgeItem(@PathVariable Long id, Authentication authentication) {
        AiKnowledge item = service.knowledge(id, storeAccessService.scopeOf(authentication.getName()));
        return ApiResponse.success(new KnowledgeResponse(item(item, storeNames()), item.content()));
    }

    @PostMapping("/knowledge")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(KNOWLEDGE)
    public ApiResponse<KnowledgeItem> createKnowledge(@RequestBody KnowledgeRequest request,
                                                      Authentication authentication) {
        return ApiResponse.success(item(service.saveKnowledge(null, request.title(), request.content(),
                request.tags(), request.storeId(), storeAccessService.scopeOf(authentication.getName()),
                authentication.getName()), storeNames()));
    }

    @PutMapping("/knowledge/{id}")
    @PreAuthorize(KNOWLEDGE)
    public ApiResponse<KnowledgeItem> updateKnowledge(@PathVariable Long id, @RequestBody KnowledgeRequest request,
                                                      Authentication authentication) {
        return ApiResponse.success(item(service.saveKnowledge(id, request.title(), request.content(),
                request.tags(), request.storeId(), storeAccessService.scopeOf(authentication.getName()),
                authentication.getName()), storeNames()));
    }

    @DeleteMapping("/knowledge/{id}")
    @PreAuthorize(KNOWLEDGE)
    public ApiResponse<Void> deleteKnowledge(@PathVariable Long id, Authentication authentication) {
        service.deleteKnowledge(id, storeAccessService.scopeOf(authentication.getName()), authentication.getName());
        return ApiResponse.success(null);
    }

    private static KnowledgeItem item(AiKnowledge item, Map<Long, String> stores) {
        String text = item.content().replaceAll("\\s+", " ").trim();
        return new KnowledgeItem(item.id(), item.title(), text.length() > 160 ? text.substring(0, 160) + "…" : text,
                item.tags() == null ? List.of() : List.of(item.tags().split(",")), item.storeId(),
                item.storeId() == null ? null : stores.get(item.storeId()), item.content().length(),
                item.createdBy(), DateTimes.format(item.updatedAt()));
    }

    // ---------- questions ----------

    /**
     * @param storeId the store asked about, or none for every store the user may access
     * @param hours   how far back logs and alerts are searched; 24 by default
     * @param history earlier turns of the conversation, oldest first
     */
    public record AskRequest(String question, Long storeId, Integer hours, List<Turn> history) {

        int range() {
            return hours == null ? 24 : hours;
        }
    }

    /**
     * The material an answer would be based on, without asking the model.
     */
    @PostMapping("/search")
    @PreAuthorize(CHAT)
    public ApiResponse<List<Source>> search(@RequestBody AskRequest request, Authentication authentication) {
        return ApiResponse.success(service.retrieve(request.question() == null ? "" : request.question(),
                request.storeId(), request.range(), storeAccessService.scopeOf(authentication.getName())).sources());
    }

    /**
     * Streams the answer as server-sent events: one {@code sources} event with the numbered material the answer cites,
     * then {@code message} events with {@code {"content"}} pieces and one {@code done} event with the whole reply.
     * A failure after the stream started is sent as an {@code error} event ({@code {"code","msg"}}).
     */
    @PostMapping(path = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize(CHAT)
    public Flux<ServerSentEvent<Object>> ask(@RequestBody AskRequest request, Authentication authentication) {
        return service.ask(request.question(), request.storeId(), request.range(), request.history(),
                        storeAccessService.scopeOf(authentication.getName()))
                .map(OpsAssistantController::toEvent)
                .onErrorResume(exception -> Flux.just(errorEvent(exception)));
    }

    private static ServerSentEvent<Object> toEvent(AskEvent event) {
        return switch (event) {
            case AskEvent.Sources sources ->
                    ServerSentEvent.builder().event("sources").data((Object) sources.sources()).build();
            case AskEvent.Model model -> switch (model.event()) {
                case AiChatStreamEvent.Delta delta -> ServerSentEvent.builder().event("message")
                        .data((Object) Map.of("content", delta.content())).build();
                case AiChatStreamEvent.Done done -> ServerSentEvent.builder().event("done")
                        .data((Object) done.reply()).build();
            };
        };
    }

    private static ServerSentEvent<Object> errorEvent(Throwable exception) {
        ApiResponse<Void> body;
        if (exception instanceof BadGatewayException badGateway) {
            body = ApiResponse.failure(HttpStatus.BAD_GATEWAY.value(), badGateway.getMessage());
        } else {
            log.error("Unexpected failure while streaming an assistant answer", exception);
            body = ApiResponse.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), "An unexpected error occurred");
        }
        return ServerSentEvent.builder().event("error").data((Object) body).build();
    }

    private Map<Long, String> storeNames() {
        Map<Long, String> names = new HashMap<>();
        logStoreRepository.findAll().forEach((LogStore store) -> names.put(store.id(), store.name()));
        return names;
    }
}
