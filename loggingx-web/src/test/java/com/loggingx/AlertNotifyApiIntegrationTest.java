package com.loggingx;

import com.loggingx.biz.monitor.LogAlertService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Log alerts and their notifications end to end, against a fake mail server and a fake Alibaba Cloud SMS endpoint.
 */
class AlertNotifyApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private LogAlertService logAlertService;

    /** Each received mail: its envelope recipients and DATA. */
    private final List<String> mails = new CopyOnWriteArrayList<>();
    /** Each SMS request's decoded query string. */
    private final List<String> smsRequests = new CopyOnWriteArrayList<>();
    private ServerSocket smtp;
    private HttpServer sms;

    @BeforeEach
    void startFakes() throws Exception {
        smtp = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!smtp.isClosed()) {
                try (Socket socket = smtp.accept()) {
                    converse(socket);
                } catch (Exception ignored) {
                    // closed
                }
            }
        });
        sms = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        sms.createContext("/", exchange -> {
            smsRequests.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] body = "{\"Code\":\"OK\",\"Message\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        sms.start();
    }

    @AfterEach
    void stopFakes() throws Exception {
        smtp.close();
        sms.stop(0);
    }

    private void converse(Socket socket) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        OutputStream out = socket.getOutputStream();
        StringBuilder mail = new StringBuilder();
        reply(out, "220 fake ESMTP");
        String line;
        int authStep = 0;
        while ((line = in.readLine()) != null) {
            if (authStep > 0) {
                reply(out, authStep++ == 1 ? "334 UGFzc3dvcmQ6" : "235 ok");
                authStep = authStep > 2 ? 0 : authStep;
                continue;
            }
            String command = line.toUpperCase();
            if (command.startsWith("EHLO")) {
                reply(out, "250-fake\r\n250 AUTH LOGIN");
            } else if (command.equals("AUTH LOGIN")) {
                authStep = 1;
                reply(out, "334 VXNlcm5hbWU6");
            } else if (command.startsWith("RCPT TO")) {
                mail.append(line).append('\n');
                reply(out, "250 ok");
            } else if (command.equals("DATA")) {
                reply(out, "354 go");
                String data;
                while (!(data = in.readLine()).equals(".")) {
                    mail.append(data).append('\n');
                }
                mails.add(mail.toString());
                reply(out, "250 queued");
            } else if (command.equals("QUIT")) {
                reply(out, "221 bye");
                return;
            } else {
                reply(out, "250 ok");
            }
        }
    }

    private static void reply(OutputStream out, String text) throws Exception {
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static <T> T await(Supplier<T> value, java.util.function.Predicate<T> done) throws Exception {
        T current = value.get();
        for (int i = 0; i < 100 && !done.test(current); i++) {
            Thread.sleep(50);
            current = value.get();
        }
        return current;
    }

    private void ingest(String ingestToken, String logs) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/logs/ingest"))
                .header("X-Log-Token", ingestToken).POST(HttpRequest.BodyPublishers.ofString(logs)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void logAlertsNotifyGroupsByEmailAndSms() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-log-alert\",\"name\":\"订单服务\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");

        // Channels: a mail server that needs a login, and Alibaba Cloud SMS at the fake endpoint.
        String email = "{\"enabled\":true,\"host\":\"127.0.0.1\",\"port\":" + smtp.getLocalPort()
                + ",\"security\":\"none\",\"username\":\"alert@example.com\",\"password\":\"mail-secret\","
                + "\"from\":\"alert@example.com\",\"fromName\":\"LoggingX\"}";
        HttpResponse<String> test = send("POST", "/api/alert-center/notify/email/test", token,
                "{\"settings\":" + email + ",\"to\":\"ops@example.com\"}");
        assertEquals(200, test.statusCode(), test.body());
        assertTrue(mails.get(0).contains("RCPT TO:<ops@example.com>"), mails.get(0));
        HttpResponse<String> savedEmail = send("PUT", "/api/alert-center/notify/email", token, email);
        assertEquals(200, savedEmail.statusCode(), savedEmail.body());
        assertFalse(savedEmail.body().contains("mail-secret"), "the password is never returned");
        assertTrue(savedEmail.body().contains("\"passwordSaved\":true"), savedEmail.body());
        String smsSettings = "{\"enabled\":true,\"provider\":\"aliyun\",\"accessKeyId\":\"LTAI-test\","
                + "\"secret\":\"sms-secret\",\"signName\":\"LoggingX\",\"templateId\":\"SMS_100\","
                + "\"endpoint\":\"http://127.0.0.1:" + sms.getAddress().getPort() + "\"}";
        HttpResponse<String> savedSms = send("PUT", "/api/alert-center/notify/sms", token, smsSettings);
        assertEquals(200, savedSms.statusCode(), savedSms.body());
        String channels = send("GET", "/api/alert-center/notify/channels", token, null).body();
        assertFalse(channels.contains("sms-secret") || channels.contains("mail-secret"), channels);
        assertEquals(400, send("PUT", "/api/alert-center/notify/sms", token,
                smsSettings.replace("aliyun", "unknown")).statusCode());

        String group = send("POST", "/api/alert-center/notify/groups", token, "{\"name\":\"订单值班\","
                + "\"userIds\":[],\"contacts\":[{\"name\":\"张三\",\"email\":\"oncall@example.com\","
                + "\"phone\":\"138 0013 8000\"},{\"name\":\"bad\",\"email\":\"\",\"phone\":\"\"}]}").body();
        String groupId = extract(group, "\"id\":(\\d+)");
        assertEquals(409, send("POST", "/api/alert-center/notify/groups", token,
                "{\"name\":\"订单值班\"}").statusCode());

        // More than 2 ERROR logs containing "timeout" within 5 minutes.
        assertEquals(400, send("POST", "/api/alert-center/log-rules", token, "{\"name\":\"no condition\","
                + "\"storeId\":" + storeId + ",\"windowMinutes\":5,\"threshold\":2,\"severity\":\"critical\"}")
                .statusCode());
        assertEquals(400, send("POST", "/api/alert-center/log-rules", token, "{\"name\":\"no recipients\","
                + "\"storeId\":" + storeId + ",\"levels\":[\"ERROR\"],\"windowMinutes\":5,\"threshold\":2,"
                + "\"severity\":\"critical\",\"notification\":{\"channels\":[\"email\"],\"userIds\":[],"
                + "\"groupIds\":[],\"notifyResolved\":true,\"repeatMinutes\":0}}").statusCode());
        HttpResponse<String> rule = send("POST", "/api/alert-center/log-rules", token, "{\"name\":\"下单超时\","
                + "\"storeId\":" + storeId + ",\"levels\":[\"error\"],\"keyword\":\"timeout\",\"windowMinutes\":5,"
                + "\"threshold\":2,\"severity\":\"critical\",\"notification\":{\"channels\":[\"email\",\"sms\"],"
                + "\"userIds\":[],\"groupIds\":[" + groupId + "],\"notifyResolved\":true,\"repeatMinutes\":0}}");
        assertEquals(201, rule.statusCode(), rule.body());
        assertTrue(rule.body().contains("\"levels\":[\"ERROR\"]"), rule.body());
        String ruleId = extract(rule.body(), "\"id\":(\\d+)");

        String now = Instant.now().minusSeconds(30).toString();
        ingest(ingestToken, "[{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"message\":\"db timeout 1\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"message\":\"db timeout 2\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"WARN\",\"message\":\"slow timeout\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"message\":\"other\"}]");
        logAlertService.evaluate(Instant.now());
        assertFalse(send("GET", "/api/alert-center/log-alerts?status=firing&storeId=" + storeId, token, null).body()
                .contains("下单超时"), "2 matching logs are not more than the threshold");

        ingest(ingestToken, "[{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"host\":\"order-1\","
                + "\"message\":\"payment Timeout <b>\"}]");
        logAlertService.evaluate(Instant.now());
        String firing = send("GET", "/api/alert-center/log-alerts?status=firing&storeId=" + storeId, token, null)
                .body();
        assertTrue(firing.contains("\"ruleName\":\"下单超时\""), firing);
        assertTrue(firing.contains("\"value\":3"), firing);

        List<String> delivered = await(() -> mails, list -> list.size() >= 2);
        String alertMail = delivered.get(1);
        assertTrue(alertMail.contains("RCPT TO:<oncall@example.com>"), alertMail);
        assertTrue(await(() -> smsRequests, list -> !list.isEmpty()).get(0).contains("PhoneNumbers=13800138000"));
        assertTrue(smsRequests.get(0).contains("\"status\":\"告警\""), smsRequests.get(0));
        String records = await(() -> {
            try {
                return send("GET", "/api/alert-center/notify/records?eventType=log", token, null).body();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, body -> body.contains("\"channel\":\"sms\"") && body.contains("\"channel\":\"email\""));
        assertTrue(records.contains("\"recipient\":\"oncall@example.com\",\"status\":\"success\""), records);
        assertFalse(records.contains("\"status\":\"failed\""), records);

        // Fewer matches resolve the alert, which is notified too.
        assertEquals(200, send("PUT", "/api/alert-center/log-rules/" + ruleId, token, "{\"name\":\"下单超时\","
                + "\"storeId\":" + storeId + ",\"levels\":[\"ERROR\"],\"keyword\":\"timeout\",\"windowMinutes\":5,"
                + "\"threshold\":10,\"severity\":\"critical\",\"notification\":{\"channels\":[\"email\"],"
                + "\"userIds\":[],\"groupIds\":[" + groupId + "],\"notifyResolved\":true,\"repeatMinutes\":0}}")
                .statusCode());
        logAlertService.evaluate(Instant.now());
        assertTrue(send("GET", "/api/alert-center/log-alerts?status=resolved&storeId=" + storeId, token, null)
                .body().contains("\"ruleName\":\"下单超时\""));
        assertEquals(3, await(() -> mails, list -> list.size() >= 3).size());
        assertEquals(1, smsRequests.size(), "the rule no longer sends SMS");

        String dashboard = send("GET", "/api/dashboard/overview?hours=1", token, null).body();
        assertTrue(dashboard.contains("\"alerts\":{"), dashboard);

        String users = send("GET", "/api/alert-center/users", token, null).body();
        assertTrue(users.contains("\"username\":\"super\""), users);
        String userToken = login("user", DEMO_PASSWORD);
        assertEquals(403, send("GET", "/api/alert-center/notify/channels", userToken, null).statusCode());

        assertEquals(200, send("DELETE", "/api/alert-center/log-rules/" + ruleId, token, null).statusCode());
        assertEquals(200, send("DELETE", "/api/alert-center/notify/groups/" + groupId, token, null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
