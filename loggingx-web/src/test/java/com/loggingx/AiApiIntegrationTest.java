package com.loggingx;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only DeepSeek is configured, pointing at a local server that answers in DeepSeek's (OpenAI-compatible) format.
 * It rejects any request whose message contains {@value #FAIL}, as the real API does a wrong key.
 */
class AiApiIntegrationTest extends ApiIntegrationTest {

    private static final String FAIL = "please fail";
    private static final String SLOW = "please hold back";
    private static final String DEEPSEEK_REPLY = """
            {"id":"ds-1","object":"chat.completion","created":1,"model":"deepseek-v4-flash",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":7,"completion_tokens":1,"total_tokens":8}}""";
    private static final String DEEPSEEK_STREAM = """
            data: {"id":"d1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant","content":"po"},"finish_reason":null}]}

            data: {"id":"d1","object":"chat.completion.chunk","created":1,"model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"ng"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":1,"total_tokens":8}}

            data: [DONE]

            """;
    private static final String REJECTED = """
            {"error":{"message":"Authentication Fails","type":"authentication_error"}}""";

    private static final HttpServer DEEPSEEK = startDeepSeek();
    private static volatile CountDownLatch firstChunkReceived = new CountDownLatch(1);
    private static volatile boolean heldBackChunkReleased;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "");
        registry.add("spring.ai.anthropic.api-key", () -> "");
        registry.add("spring.ai.deepseek.api-key", () -> "test-key");
        registry.add("spring.ai.deepseek.base-url",
                () -> "http://127.0.0.1:" + DEEPSEEK.getAddress().getPort());
    }

    @AfterAll
    static void stopDeepSeek() {
        DEEPSEEK.stop(0);
    }

    @Test
    void listsConfiguredProviders() throws Exception {
        HttpResponse<String> response = send("GET", "/api/ai/providers", login("user", DEMO_PASSWORD), null);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"data\":[\"deepseek\"]"), response.body());
    }

    @Test
    void chatsWithConfiguredProvider() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat", login("user", DEMO_PASSWORD),
                "{\"provider\":\"deepseek\",\"message\":\"ping\"}");

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"content\":\"pong\""), response.body());
        assertTrue(response.body().contains("\"model\":\"deepseek-v4-flash\""), response.body());
        assertTrue(response.body().contains("\"promptTokens\":7"), response.body());
    }

    @Test
    void rejectsUnconfiguredProvider() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat", login("user", DEMO_PASSWORD),
                "{\"provider\":\"openai\",\"message\":\"ping\"}");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("not configured"), response.body());
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat", login("user", DEMO_PASSWORD),
                "{\"provider\":\"deepseek\",\"message\":\" \"}");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("message must not be blank"), response.body());
    }

    @Test
    void reportsProviderFailureAsBadGateway() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat", login("user", DEMO_PASSWORD),
                "{\"provider\":\"deepseek\",\"message\":\"" + FAIL + "\"}");

        assertEquals(502, response.statusCode(), response.body());
        assertTrue(response.body().contains("Authentication Fails"), response.body());
    }

    @Test
    void requiresAuthentication() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat", null,
                "{\"provider\":\"deepseek\",\"message\":\"ping\"}");

        assertEquals(401, response.statusCode(), response.body());
    }

    @Test
    void streamsAnswerAsServerSentEvents() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat/stream", login("user", DEMO_PASSWORD),
                "{\"provider\":\"deepseek\",\"message\":\"ping\"}", "text/event-stream");

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"),
                response.headers().toString());
        String body = response.body().replace("\r\n", "\n");
        assertTrue(body.contains("event:message\ndata:{\"content\":\"po\"}\n\n"
                + "event:message\ndata:{\"content\":\"ng\"}\n\n"
                + "event:done\ndata:{\"provider\":\"deepseek\",\"model\":\"deepseek-v4-flash\",\"content\":\"pong\","
                + "\"promptTokens\":7,\"completionTokens\":1}\n\n"), body);
    }

    /**
     * The provider holds back its second chunk until the client has received the first, which only happens if
     * events are flushed as they arrive rather than buffered until the answer is complete.
     */
    @Test
    void streamsEventsBeforeTheAnswerIsComplete() throws Exception {
        firstChunkReceived = new CountDownLatch(1);
        heldBackChunkReleased = false;
        HttpRequest request = HttpRequest.newBuilder(uri("/api/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + login("user", DEMO_PASSWORD))
                .POST(HttpRequest.BodyPublishers.ofString("{\"provider\":\"deepseek\",\"message\":\"" + SLOW + "\"}"))
                .build();

        List<String> lines = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines())
                .body()
                .peek(line -> {
                    if (line.equals("data:{\"content\":\"po\"}")) {
                        firstChunkReceived.countDown();
                    }
                })
                .toList();

        assertTrue(heldBackChunkReleased, "first event was not delivered before the stream completed");
        assertTrue(lines.contains("data:{\"content\":\"ng\"}"), lines.toString());
        assertTrue(lines.contains("event:done"), lines.toString());
    }

    @Test
    void streamReportsProviderFailureAsErrorEvent() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat/stream", login("user", DEMO_PASSWORD),
                "{\"provider\":\"deepseek\",\"message\":\"" + FAIL + "\"}", "text/event-stream");

        assertEquals(200, response.statusCode(), response.body());
        String body = response.body().replace("\r\n", "\n");
        assertTrue(body.startsWith("event:error\ndata:{\"code\":502,\"msg\":\"AI provider 'deepseek' request failed"),
                body);
        assertTrue(!body.contains("event:done"), body);
    }

    @Test
    void streamRejectsInvalidRequestBeforeStreaming() throws Exception {
        String token = login("user", DEMO_PASSWORD);

        HttpResponse<String> unconfigured = send("POST", "/api/ai/chat/stream", token,
                "{\"provider\":\"openai\",\"message\":\"ping\"}", "text/event-stream");
        assertEquals(400, unconfigured.statusCode(), unconfigured.body());
        assertTrue(unconfigured.body().contains("not configured"), unconfigured.body());

        HttpResponse<String> blank = send("POST", "/api/ai/chat/stream", token,
                "{\"provider\":\"deepseek\",\"message\":\"\"}", "text/event-stream");
        assertEquals(400, blank.statusCode(), blank.body());
        assertTrue(blank.body().contains("message must not be blank"), blank.body());
    }

    @Test
    void streamRequiresAuthentication() throws Exception {
        HttpResponse<String> response = send("POST", "/api/ai/chat/stream", null,
                "{\"provider\":\"deepseek\",\"message\":\"ping\"}", "text/event-stream");

        assertEquals(401, response.statusCode(), response.body());
    }

    private static HttpServer startDeepSeek() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (request.contains(SLOW)) {
                    streamSlowly(exchange);
                    return;
                }
                boolean stream = request.contains("\"stream\":true");
                int status = request.contains(FAIL) ? 401 : 200;
                String reply = status == 401 ? REJECTED : stream ? DEEPSEEK_STREAM : DEEPSEEK_REPLY;
                byte[] body = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type",
                        status == 200 && stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void streamSlowly(HttpExchange exchange) throws IOException {
        String[] chunks = DEEPSEEK_STREAM.split("(?<=\n\n)", 2);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write(chunks[0].getBytes(StandardCharsets.UTF_8));
        out.flush();
        try {
            heldBackChunkReleased = firstChunkReceived.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        out.write(chunks[1].getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }
}
