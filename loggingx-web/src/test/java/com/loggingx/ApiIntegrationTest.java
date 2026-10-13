package com.loggingx;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base for end-to-end tests against the running server and the {@code dev} demo data.
 * <p>
 * Subclasses share one application context, and so one in-memory store: they must not depend on state that
 * another test changes, such as the {@code user} account's password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
abstract class ApiIntegrationTest {

    protected static final String DEMO_PASSWORD = "123456";

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    protected HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> attemptLogin(String username, String password) throws Exception {
        return send("POST", "/api/auth/login", null,
                "{\"userName\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    protected String login(String username, String password) throws Exception {
        HttpResponse<String> response = attemptLogin(username, password);
        assertEquals(200, response.statusCode(), response.body());
        return extract(response.body(), "\"token\":\"([^\"]+)\"");
    }

    protected static String extract(String json, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }
}
