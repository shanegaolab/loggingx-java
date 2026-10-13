package com.loggingx;

import org.junit.jupiter.api.Test;
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
 * End-to-end check against the demo data that tokens are bound to the account id and its token version.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class TokenRevocationIntegrationTest {

    private static final String DEMO_PASSWORD = "123456";

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
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

    private String login(String username, String password) throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/login", null,
                "{\"userName\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        return extract(response.body(), "\"token\":\"([^\"]+)\"");
    }

    private static String extract(String json, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    @Test
    void changingPasswordRevokesExistingTokens() throws Exception {
        String superToken = login("super", DEMO_PASSWORD);
        String userToken = login("user", DEMO_PASSWORD);

        HttpResponse<String> info = send("GET", "/api/user/info", userToken, null);
        assertEquals(200, info.statusCode(), info.body());
        String userId = extract(info.body(), "\"userId\":(\\d+)");

        HttpResponse<String> update = send("PUT", "/api/user/" + userId, superToken,
                "{\"userName\":\"user\",\"password\":\"new-password-1\",\"status\":\"1\",\"userRoles\":[\"R_USER\"]}");
        assertEquals(200, update.statusCode(), update.body());

        assertEquals(401, send("GET", "/api/user/info", userToken, null).statusCode());
        String newToken = login("user", "new-password-1");
        assertEquals(200, send("GET", "/api/user/info", newToken, null).statusCode());
        // The operator's own token is unaffected.
        assertEquals(200, send("GET", "/api/user/info", superToken, null).statusCode());
    }
}
