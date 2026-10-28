package com.loggingx;

import com.loggingx.biz.ai.AiDiagnosisService;
import com.loggingx.biz.monitor.LogAlertService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI diagnosis end to end: a store linked to a Git repository, errors grouped by fingerprint, a new kind of error
 * analysed with the code its stack trace points to, against a fake DeepSeek.
 */
class AiDiagnosisApiIntegrationTest extends ApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ANSWER = """
            {"summary":"库存扣减前没有检查剩余数量","rootCause":"reserve 直接扣减库存，数量不足时变成负数后才抛出异常。",
             "suggestion":"在扣减前检查库存，不足时返回明确的业务错误。",
             "diff":"--- a/src/main/java/com/shop/OrderService.java\\n+++ b/src/main/java/com/shop/OrderService.java\\n@@ -5,3 +5,4 @@\\n+        if (stock < quantity) throw new OutOfStockException(sku);\\n",
             "confidence":"high"}""";
    private static final List<String> PROMPTS = new CopyOnWriteArrayList<>();
    private static final HttpServer DEEPSEEK = startDeepSeek();

    @Autowired
    private AiDiagnosisService aiDiagnosisService;

    @Autowired
    private LogAlertService logAlertService;

    @DynamicPropertySource
    static void aiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.api-key", () -> "");
        registry.add("spring.ai.anthropic.api-key", () -> "");
        registry.add("spring.ai.deepseek.api-key", () -> "test-key");
        registry.add("spring.ai.deepseek.base-url", () -> "http://127.0.0.1:" + DEEPSEEK.getAddress().getPort());
        registry.add("loggingx.ai.repo-dir", () -> tempDir("loggingx-it-repos").toString());
    }

    @AfterAll
    static void stopDeepSeek() {
        DEEPSEEK.stop(0);
    }

    private static HttpServer startDeepSeek() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                PROMPTS.add(request);
                if (request.contains("\"stream\":true")) {
                    byte[] body = (chunk("根据处理手册，库存为负是因为扣减前没有校验 [1]。") + chunk("建议先回滚订单。")
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                ObjectNode reply = JSON.createObjectNode();
                reply.put("id", "ds-1").put("object", "chat.completion").put("created", 1)
                        .put("model", "deepseek-v4-flash");
                ObjectNode choice = reply.putArray("choices").addObject();
                choice.put("index", 0).put("finish_reason", "stop");
                choice.putObject("message").put("role", "assistant").put("content", ANSWER);
                reply.putObject("usage").put("prompt_tokens", 900).put("completion_tokens", 120)
                        .put("total_tokens", 1020);
                byte[] body = JSON.writeValueAsBytes(reply);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String chunk(String content) {
        ObjectNode chunk = JSON.createObjectNode();
        chunk.put("id", "d1").put("object", "chat.completion.chunk").put("created", 1)
                .put("model", "deepseek-v4-flash");
        ObjectNode choice = chunk.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta").put("content", content);
        return "data: " + JSON.writeValueAsString(chunk) + "\n\n";
    }

    private static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    /**
     * A repository with the class the errors come from: line 6 subtracts the stock, line 7 throws.
     */
    private static String createRepository() throws Exception {
        Path repo = tempDir("loggingx-it-shop");
        Path source = repo.resolve("src/main/java/com/shop/OrderService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.shop;

                public class OrderService {

                    public void reserve(String sku, int quantity) {
                        stock = stock - quantity; // STOCK-LINE
                        if (stock < 0) throw new IllegalStateException("stock is negative for sku " + sku);
                    }

                    private int stock;
                }
                """);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "-c", "user.name=it", "-c", "user.email=it@example.com", "add", ".");
        git(repo, "-c", "user.name=it", "-c", "user.email=it@example.com", "commit", "-q", "-m", "init");
        return "file:///" + repo.toString().replace('\\', '/').replaceFirst("^/", "");
    }

    private void ingest(String ingestToken, int line, String sku, Instant time) throws Exception {
        String message = "submit failed\\njava.lang.IllegalStateException: stock is negative for sku " + sku
                + "\\n\\tat com.shop.OrderService.reserve(OrderService.java:" + line + ")"
                + "\\n\\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)";
        String body = "[{\"time\":\"" + time + "\",\"level\":\"ERROR\",\"host\":\"order-1\","
                + "\"source\":\"/var/log/order/app.log\",\"message\":\"" + message + "\"}]";
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/logs/ingest"))
                .header("X-Log-Token", ingestToken).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private String awaitDiagnosis(String token, String storeId) throws Exception {
        String body = "";
        for (int i = 0; i < 100; i++) {
            body = send("GET", "/api/ai-diagnosis/diagnoses?storeId=" + storeId, token, null).body();
            if (body.contains("\"status\":\"success\"") || body.contains("\"status\":\"failed\"")) {
                return body;
            }
            Thread.sleep(100);
        }
        return body;
    }

    @Test
    void newErrorsAreAnalysedWithTheirCode() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-ai\",\"name\":\"Orders\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");

        HttpResponse<String> settings = send("PUT", "/api/ai-diagnosis/settings", token,
                "{\"provider\":\"deepseek\",\"dailyLimit\":5}");
        assertEquals(200, settings.statusCode(), settings.body());
        assertTrue(settings.body().contains("\"providers\":[\"deepseek\"]"), settings.body());
        assertEquals(400, send("PUT", "/api/ai-diagnosis/stores/" + storeId, token,
                "{\"repoUrl\":\"ssh://git@example.com/shop.git\"}").statusCode());
        HttpResponse<String> saved = send("PUT", "/api/ai-diagnosis/stores/" + storeId, token,
                "{\"repoUrl\":\"" + createRepository() + "\",\"repoBranch\":\"main\",\"autoNew\":true}");
        assertEquals(200, saved.statusCode(), saved.body());
        HttpResponse<String> synced = send("POST", "/api/ai-diagnosis/stores/" + storeId + "/sync", token, null);
        assertEquals(200, synced.statusCode(), synced.body());
        assertTrue(synced.body().contains("\"files\":1"), synced.body());

        // The first occurrence is a new kind of error and is analysed; the second (another line) is the same kind.
        Instant now = Instant.now();
        ingest(ingestToken, 7, "1029", now);
        aiDiagnosisService.scan(now.plusSeconds(15));
        ingest(ingestToken, 8, "77", now.plusSeconds(20));
        aiDiagnosisService.scan(now.plusSeconds(40));
        String groups = send("GET", "/api/ai-diagnosis/groups?storeId=" + storeId, token, null).body();
        assertTrue(groups.contains("\"errorType\":\"java.lang.IllegalStateException\""), groups);
        assertTrue(groups.contains("\"occurrences\":2"), groups);
        assertTrue(groups.contains("com.shop.OrderService.reserve"), groups);

        String list = awaitDiagnosis(token, storeId);
        assertTrue(list.contains("\"status\":\"success\""), list);
        assertTrue(list.contains("\"triggerType\":\"auto\""), list);
        assertEquals(1, list.split("\"triggerType\"").length - 1, "one analysis per kind of error: " + list);
        String id = extract(list, "\"id\":(\\d+)");
        String detail = send("GET", "/api/ai-diagnosis/diagnoses/" + id, token, null).body();
        assertTrue(detail.contains("\"summary\":\"库存扣减前没有检查剩余数量\""), detail);
        assertTrue(detail.contains("\"confidence\":\"high\""), detail);
        assertTrue(detail.contains("\"path\":\"src/main/java/com/shop/OrderService.java\""), detail);
        assertTrue(detail.contains("\"focusLine\":7"), detail);
        assertTrue(detail.contains("OutOfStockException"), "the diff is kept: " + detail);
        assertTrue(PROMPTS.get(0).contains("STOCK-LINE"), "the code goes to the model: " + PROMPTS.get(0));

        // The bell shows it until read.
        String notices = send("GET", "/api/notices", token, null).body();
        assertTrue(notices.contains("AI 诊断：java.lang.IllegalStateException"), notices);
        assertFalse(notices.contains("\"unread\":0"), notices);
        assertEquals(200, send("POST", "/api/notices/read", token, null).statusCode());
        assertTrue(send("GET", "/api/notices", token, null).body().contains("\"unread\":0"));

        // Resolving the analysis resolves its kind of error.
        assertEquals(200, send("PUT", "/api/ai-diagnosis/diagnoses/" + id + "/state", token,
                "{\"state\":\"resolved\"}").statusCode());
        assertTrue(send("GET", "/api/ai-diagnosis/groups?storeId=" + storeId, token, null).body()
                .contains("\"status\":\"resolved\""));

        // Anyone with the page can ask for an analysis of a log.
        HttpResponse<String> manual = send("POST", "/api/ai-diagnosis/analyze", token, "{\"storeId\":" + storeId
                + ",\"message\":\"payment timeout\\n\\tat com.shop.OrderService.reserve(OrderService.java:6)\"}");
        assertEquals(202, manual.statusCode(), manual.body());
        assertTrue(manual.body().contains("\"triggerType\":\"manual\""), manual.body());

        // A log alert that fires is analysed when the store asks for it.
        assertEquals(200, send("PUT", "/api/ai-diagnosis/stores/" + storeId, token,
                "{\"repoUrl\":\"\",\"autoNew\":false,\"onAlert\":true}").statusCode());
        assertEquals(201, send("POST", "/api/alert-center/log-rules", token, "{\"name\":\"stock errors\","
                + "\"storeId\":" + storeId + ",\"levels\":[\"ERROR\"],\"keyword\":\"stock\",\"windowMinutes\":5,"
                + "\"threshold\":0,\"severity\":\"critical\"}").statusCode());
        logAlertService.evaluate(Instant.now().plusSeconds(25));
        String alerted = "";
        for (int i = 0; i < 100 && !alerted.contains("\"triggerType\":\"alert\""); i++) {
            Thread.sleep(100);
            alerted = send("GET", "/api/ai-diagnosis/diagnoses?trigger=alert&storeId=" + storeId, token, null).body();
        }
        assertTrue(alerted.contains("\"triggerType\":\"alert\""), alerted);

        assertEquals(403, send("GET", "/api/ai-diagnosis/diagnoses", login("user", DEMO_PASSWORD), null)
                .statusCode(), "users without the menu");
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    /**
     * The assistant answers from the knowledge base and the logs, citing them, and streams the answer.
     */
    @Test
    void theAssistantAnswersFromKnowledgeAndLogs() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-ai-ops\",\"name\":\"Stock\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        ingest(extract(created, "\"ingestToken\":\"([^\"]+)\""), 7, "5501", Instant.now().minusSeconds(60));

        HttpResponse<String> doc = send("POST", "/api/ai-ops/knowledge", token, "{\"title\":\"库存为负处理手册\","
                + "\"tags\":\"订单, 库存\",\"content\":\"# 现象\\n订单服务报 IllegalStateException: stock is negative。"
                + "\\n\\n# 处理\\n先回滚订单，再检查扣减前是否校验库存（ROLLBACK-FIRST）。\"}");
        assertEquals(201, doc.statusCode(), doc.body());
        assertTrue(doc.body().contains("\"tags\":[\"订单\",\"库存\"]"), doc.body());
        send("POST", "/api/ai-ops/knowledge", token, "{\"title\":\"磁盘清理\",\"content\":\"磁盘满时清理 /var/log 下的旧日志。\"}");

        String question = "{\"question\":\"订单服务为什么报 stock is negative？库存为负怎么处理\",\"storeId\":" + storeId
                + ",\"hours\":24}";
        HttpResponse<String> found = send("POST", "/api/ai-ops/search", token, question);
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("\"title\":\"库存为负处理手册\""), found.body());
        assertFalse(found.body().contains("磁盘清理"), "unrelated documents are left out: " + found.body());
        assertTrue(found.body().contains("\"type\":\"log\""), "recent errors are included: " + found.body());
        assertTrue(found.body().contains("\"type\":\"stats\""), found.body());

        int before = PROMPTS.size();
        HttpResponse<String> answer = send("POST", "/api/ai-ops/ask/stream", token, question, "text/event-stream");
        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(answer.body().indexOf("event:sources") < answer.body().indexOf("event:message"), answer.body());
        assertTrue(answer.body().contains("库存为负是因为扣减前没有校验 [1]"), answer.body());
        assertTrue(answer.body().contains("event:done"), answer.body());
        String prompt = PROMPTS.get(before);
        assertTrue(prompt.contains("ROLLBACK-FIRST") && prompt.contains("stock is negative for sku 5501"), prompt);

        assertEquals(400, send("POST", "/api/ai-ops/ask/stream", token, "{\"question\":\" \"}").statusCode());
        assertEquals(403, send("GET", "/api/ai-ops/knowledge", login("user", DEMO_PASSWORD), null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
