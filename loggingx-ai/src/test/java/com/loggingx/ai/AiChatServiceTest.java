package com.loggingx.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loggingx.ai.config.AiConfiguration;
import com.loggingx.common.exception.BadGatewayException;
import com.loggingx.common.exception.BadRequestException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Runs each provider through Spring AI against a local server that answers in that provider's wire format.
 */
class AiChatServiceTest {

    private static final String OPENAI_REPLY = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5-mini",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong from openai"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}""";
    private static final String ANTHROPIC_REPLY = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5",
             "content":[{"type":"text","text":"pong from claude"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":12,"output_tokens":4}}""";
    private static final String DEEPSEEK_REPLY = """
            {"id":"ds-1","object":"chat.completion","created":1,"model":"deepseek-v4-flash",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong from deepseek"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":13,"completion_tokens":5,"total_tokens":18}}""";
    private static final String REJECTED = """
            {"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""";

    // Streamed replies: the text arrives in two pieces, usage on a later (OpenAI, DeepSeek: last) chunk.
    private static final String OPENAI_STREAM = """
            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"pong "},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5-mini","choices":[{"index":0,"delta":{"content":"from openai"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-5-mini","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}

            data: [DONE]

            """;
    private static final String ANTHROPIC_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"pong "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"from claude"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    private static final String DEEPSEEK_STREAM = """
            data: {"id":"d1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"pong "},"finish_reason":null}]}

            data: {"id":"d1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"from deepseek"},"finish_reason":null}]}

            data: {"id":"d1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":13,"completion_tokens":5,"total_tokens":18}}

            data: [DONE]

            """;

    private final Map<String, String> requestBodies = new ConcurrentHashMap<>();
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();
    private HttpServer server;
    private String baseUrl;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
            .withUserConfiguration(AiConfiguration.class);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/openai/v1/chat/completions", "Authorization", 200, OPENAI_REPLY, OPENAI_STREAM);
        respond("/anthropic/v1/messages", "x-api-key", 200, ANTHROPIC_REPLY, ANTHROPIC_STREAM);
        respond("/deepseek/chat/completions", "Authorization", 200, DEEPSEEK_REPLY, DEEPSEEK_STREAM);
        respond("/rejecting/", "Authorization", 401, REJECTED, null);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void noApiKeyMeansNoProviders() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AiChatService service = context.getBean(AiChatService.class);
            assertThat(service.providers()).isEmpty();
            assertThatThrownBy(() -> service.chat(new AiChatCommand("openai", "ping", null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not configured");
        });
    }

    @Test
    void chatsWithEveryConfiguredProvider() {
        allProviders().run(context -> {
            AiChatService service = context.getBean(AiChatService.class);
            assertThat(service.providers())
                    .containsExactly(AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.DEEPSEEK);

            assertThat(service.chat(new AiChatCommand("openai", "ping", "Be brief.", null)))
                    .isEqualTo(new AiChatReply("openai", "gpt-5-mini", "pong from openai", 11, 3));
            assertThat(service.chat(new AiChatCommand("anthropic", "ping", "Be brief.", null)))
                    .isEqualTo(new AiChatReply("anthropic", "claude-haiku-4-5", "pong from claude", 12, 4));
            assertThat(service.chat(new AiChatCommand(" DeepSeek ", "ping", "Be brief.", null)))
                    .isEqualTo(new AiChatReply("deepseek", "deepseek-v4-flash", "pong from deepseek", 13, 5));
        });

        assertThat(authHeaders)
                .containsEntry("openai", "Bearer openai-key")
                .containsEntry("anthropic", "anthropic-key")
                .containsEntry("deepseek", "Bearer deepseek-key");
        assertThat(requestBodies.get("openai")).contains("\"gpt-5-mini\"", "Be brief.", "ping");
        assertThat(requestBodies.get("anthropic")).contains("\"claude-haiku-4-5\"", "Be brief.", "ping", "max_tokens");
        assertThat(requestBodies.get("deepseek")).contains("\"deepseek-v4-flash\"", "Be brief.", "ping");
    }

    @Test
    void requestModelOverridesTheConfiguredDefault() {
        allProviders().run(context -> {
            AiChatService service = context.getBean(AiChatService.class);
            service.chat(new AiChatCommand("openai", "ping", null, "gpt-5"));
            service.chat(new AiChatCommand("anthropic", "ping", null, "claude-sonnet-4-5"));
            service.chat(new AiChatCommand("deepseek", "ping", null, "deepseek-v4-pro"));
        });

        assertThat(requestBodies.get("openai")).contains("\"gpt-5\"");
        // Provider defaults that the request does not override, such as Anthropic's max_tokens, are kept.
        assertThat(requestBodies.get("anthropic")).contains("\"claude-sonnet-4-5\"", "max_tokens");
        assertThat(requestBodies.get("deepseek")).contains("\"deepseek-v4-pro\"");
    }

    @Test
    void onlyProvidersWithAKeyAreRegistered() {
        contextRunner
                .withPropertyValues("spring.ai.deepseek.api-key=deepseek-key", "spring.ai.openai.api-key=  ")
                .run(context -> assertThat(context.getBean(AiChatService.class).providers())
                        .containsExactly(AiProvider.DEEPSEEK));
    }

    @Test
    void unknownProviderIsABadRequest() {
        allProviders().run(context -> assertThatThrownBy(() -> context.getBean(AiChatService.class)
                .chat(new AiChatCommand("gemini", "ping", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown AI provider"));
    }

    @Test
    void providerErrorsBecomeBadGateway() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.api-key=wrong", "spring.ai.openai.base-url=" + baseUrl + "/rejecting/v1",
                        "spring.ai.anthropic.api-key=wrong", "spring.ai.anthropic.base-url=" + baseUrl + "/rejecting",
                        "spring.ai.deepseek.api-key=wrong", "spring.ai.deepseek.base-url=" + baseUrl + "/rejecting")
                .run(context -> {
                    AiChatService service = context.getBean(AiChatService.class);
                    for (String provider : List.of("openai", "anthropic", "deepseek")) {
                        assertThatThrownBy(() -> service.chat(new AiChatCommand(provider, "ping", null, null)))
                                .isInstanceOf(BadGatewayException.class)
                                .hasMessageContaining(provider)
                                .hasMessageContaining("401");
                    }
                });
    }

    @Test
    void streamsFromEveryConfiguredProvider() {
        allProviders().run(context -> {
            AiChatService service = context.getBean(AiChatService.class);

            assertThat(streamed(service, "openai")).containsExactly(
                    new AiChatStreamEvent.Delta("pong "),
                    new AiChatStreamEvent.Delta("from openai"),
                    new AiChatStreamEvent.Done(new AiChatReply("openai", "gpt-5-mini", "pong from openai", 11, 3)));
            assertThat(streamed(service, "anthropic")).containsExactly(
                    new AiChatStreamEvent.Delta("pong "),
                    new AiChatStreamEvent.Delta("from claude"),
                    new AiChatStreamEvent.Done(
                            new AiChatReply("anthropic", "claude-haiku-4-5", "pong from claude", 12, 4)));
            assertThat(streamed(service, "deepseek")).containsExactly(
                    new AiChatStreamEvent.Delta("pong "),
                    new AiChatStreamEvent.Delta("from deepseek"),
                    new AiChatStreamEvent.Done(
                            new AiChatReply("deepseek", "deepseek-v4-flash", "pong from deepseek", 13, 5)));
        });

        assertThat(requestBodies.values()).allSatisfy(body -> assertThat(body).contains("\"stream\":true"));
    }

    @Test
    void streamRejectsUnconfiguredProviderBeforeStarting() {
        contextRunner.withPropertyValues("spring.ai.deepseek.api-key=deepseek-key")
                .run(context -> assertThatThrownBy(() -> context.getBean(AiChatService.class)
                        .stream(new AiChatCommand("openai", "ping", null, null)))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("not configured"));
    }

    @Test
    void streamProviderErrorsBecomeBadGateway() {
        contextRunner
                .withPropertyValues(
                        "spring.ai.openai.api-key=wrong", "spring.ai.openai.base-url=" + baseUrl + "/rejecting/v1",
                        "spring.ai.anthropic.api-key=wrong", "spring.ai.anthropic.base-url=" + baseUrl + "/rejecting",
                        "spring.ai.deepseek.api-key=wrong", "spring.ai.deepseek.base-url=" + baseUrl + "/rejecting")
                .run(context -> {
                    AiChatService service = context.getBean(AiChatService.class);
                    for (String provider : List.of("openai", "anthropic", "deepseek")) {
                        assertThatThrownBy(() -> streamed(service, provider))
                                .isInstanceOf(BadGatewayException.class)
                                .hasMessageContaining(provider)
                                .hasMessageContaining("401");
                    }
                });
    }

    private static List<AiChatStreamEvent> streamed(AiChatService service, String provider) {
        return service.stream(new AiChatCommand(provider, "ping", null, null))
                .collectList()
                .block(Duration.ofSeconds(30));
    }

    private ApplicationContextRunner allProviders() {
        return contextRunner.withPropertyValues(
                "spring.ai.openai.api-key=openai-key",
                "spring.ai.openai.base-url=" + baseUrl + "/openai/v1",
                "spring.ai.openai.chat.options.model=gpt-5-mini",
                "spring.ai.anthropic.api-key=anthropic-key",
                "spring.ai.anthropic.base-url=" + baseUrl + "/anthropic",
                "spring.ai.anthropic.chat.options.model=claude-haiku-4-5",
                "spring.ai.deepseek.api-key=deepseek-key",
                "spring.ai.deepseek.base-url=" + baseUrl + "/deepseek",
                "spring.ai.deepseek.chat.options.model=deepseek-v4-flash");
    }

    /**
     * Answers {@code path} with a JSON body, or with {@code streamReply} as server-sent events when the request asks
     * for a stream, recording the request body and authentication header by the path's first segment.
     */
    private void respond(String path, String authHeader, int status, String reply, String streamReply) {
        String name = path.substring(1, path.indexOf('/', 1));
        server.createContext(path, exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.put(name, request);
            String credentials = exchange.getRequestHeaders().getFirst(authHeader);
            if (credentials != null) {
                authHeaders.put(name, credentials);
            }
            boolean stream = streamReply != null && request.contains("\"stream\":true");
            byte[] body = (stream ? streamReply : reply).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }
}
