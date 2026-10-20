package com.loggingx.ai;

import com.loggingx.common.exception.ApplicationException;
import com.loggingx.common.exception.BadGatewayException;
import com.loggingx.common.exception.BadRequestException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sends chat requests to whichever providers have an API key configured (see {@code AiConfiguration}).
 */
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final Map<AiProvider, ChatClient> clients;

    public AiChatService(Map<AiProvider, ChatModel> models) {
        Map<AiProvider, ChatClient> built = new EnumMap<>(AiProvider.class);
        models.forEach((provider, model) -> built.put(provider, ChatClient.create(model)));
        this.clients = built;
    }

    /**
     * The configured providers, in declaration order.
     */
    public List<AiProvider> providers() {
        return List.copyOf(clients.keySet());
    }

    public AiChatReply chat(AiChatCommand command) {
        AiProvider provider = providerOf(command);
        ChatResponse response;
        try {
            response = prompt(provider, command).call().chatResponse();
        } catch (RuntimeException exception) {
            throw providerFailure(provider, command, exception);
        }
        if (response == null || response.getResult() == null) {
            throw new BadGatewayException("AI provider '" + provider.id() + "' returned no answer");
        }

        ChatResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        return new AiChatReply(
                provider.id(),
                metadata.getModel(),
                response.getResult().getOutput().getText(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    /**
     * Streams the answer as it is generated. An unknown or unconfigured provider is rejected before the stream
     * starts; failures reported by the provider end the stream with a {@link BadGatewayException}. Cancelling the
     * subscription (e.g. the client disconnecting) cancels the request to the provider.
     */
    public Flux<AiChatStreamEvent> stream(AiChatCommand command) {
        AiProvider provider = providerOf(command);
        ChatClient.ChatClientRequestSpec request = prompt(provider, command);
        return Flux.defer(() -> {
            StreamedReply reply = new StreamedReply(provider);
            return request.stream().chatResponse()
                    .<AiChatStreamEvent>handle((response, sink) -> {
                        String content = reply.append(response);
                        if (StringUtils.hasLength(content)) {
                            sink.next(new AiChatStreamEvent.Delta(content));
                        }
                    })
                    .concatWith(Mono.fromSupplier(() -> new AiChatStreamEvent.Done(reply.toReply())));
        }).onErrorMap(
                exception -> !(exception instanceof ApplicationException),
                exception -> providerFailure(provider, command, exception));
    }

    private AiProvider providerOf(AiChatCommand command) {
        AiProvider provider = AiProvider.fromId(command.provider())
                .orElseThrow(() -> new BadRequestException("Unknown AI provider: " + command.provider()));
        if (!clients.containsKey(provider)) {
            throw new BadRequestException("AI provider '" + provider.id() + "' is not configured");
        }
        return provider;
    }

    private ChatClient.ChatClientRequestSpec prompt(AiProvider provider, AiChatCommand command) {
        ChatClient.ChatClientRequestSpec request = clients.get(provider).prompt().user(command.message());
        if (StringUtils.hasText(command.systemPrompt())) {
            request = request.system(command.systemPrompt());
        }
        if (StringUtils.hasText(command.model())) {
            request = request.options(ChatOptions.builder().model(command.model().trim()));
        }
        return request;
    }

    /**
     * Covers authentication failures, rate limits, unknown models and network errors reported by the provider.
     */
    private static BadGatewayException providerFailure(AiProvider provider, AiChatCommand command, Throwable exception) {
        log.warn("event=ai.chat.failed provider={} model={}", provider.id(), command.model(), exception);
        return new BadGatewayException("AI provider '" + provider.id() + "' request failed: " + describe(exception));
    }

    /**
     * The SDKs wrap network failures in a generic "Request failed"; the root cause (e.g. a TLS handshake or
     * connection error) is what tells the caller what went wrong.
     */
    private static String describe(Throwable exception) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(exception);
        return root == exception || root.getMessage() == null
                ? exception.getMessage()
                : exception.getMessage() + " (" + root.getMessage() + ")";
    }

    /**
     * Collects the chunks of one streamed answer. Providers report the model and token usage on some chunks only
     * (often the first or the last), so the latest non-empty value of each is kept.
     */
    private static final class StreamedReply {

        private final AiProvider provider;
        private final StringBuilder content = new StringBuilder();
        private String model;
        private Integer promptTokens;
        private Integer completionTokens;

        StreamedReply(AiProvider provider) {
            this.provider = provider;
        }

        /**
         * Records the chunk and returns its text, which may be empty (e.g. a chunk that only carries usage).
         */
        String append(ChatResponse response) {
            ChatResponseMetadata metadata = response.getMetadata();
            if (StringUtils.hasText(metadata.getModel())) {
                model = metadata.getModel();
            }
            Usage usage = metadata.getUsage();
            if (usage != null) {
                promptTokens = positiveOr(usage.getPromptTokens(), promptTokens);
                completionTokens = positiveOr(usage.getCompletionTokens(), completionTokens);
            }
            String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text != null) {
                content.append(text);
            }
            return text;
        }

        AiChatReply toReply() {
            return new AiChatReply(provider.id(), model, content.toString(), promptTokens, completionTokens);
        }

        private static Integer positiveOr(Integer value, Integer fallback) {
            return value != null && value > 0 ? value : fallback;
        }
    }
}
