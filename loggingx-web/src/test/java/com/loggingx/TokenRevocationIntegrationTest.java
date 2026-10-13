package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end check against the demo data that tokens are bound to the account id and its token version.
 */
class TokenRevocationIntegrationTest extends ApiIntegrationTest {

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
