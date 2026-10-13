package com.loggingx;

import com.loggingx.auth.service.LoginAttemptLimiter;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessControlIntegrationTest extends ApiIntegrationTest {

    @Test
    void rejectedRequestsUseTheResponseEnvelope() throws Exception {
        HttpResponse<String> response = send("GET", "/api/user/info", null, null);

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"code\":401"), response.body());
    }

    @Test
    void administratorCannotGrantPermissionsTheyDoNotHold() throws Exception {
        String superToken = login("super", DEMO_PASSWORD);
        String adminToken = login("admin", DEMO_PASSWORD);

        // A role with menu management, which the demo admin role does not have.
        HttpResponse<String> created = send("POST", "/api/role", superToken,
                "{\"roleName\":\"Menu admin\",\"roleCode\":\"R_MENU_ADMIN\",\"enabled\":true}");
        assertEquals(201, created.statusCode(), created.body());
        String roleId = extract(created.body(), "\"roleId\":(\\d+)");
        String tree = send("GET", "/api/menu/tree", superToken, null).body();
        String menusId = extract(tree, "\"id\":(\\d+),\"parentId\":\\d+,\"path\":\"[^\"]*\",\"name\":\"Menus\"");
        HttpResponse<String> assigned = send("PUT", "/api/role/" + roleId + "/menus", superToken,
                "{\"menuIds\":[" + menusId + "]}");
        assertEquals(200, assigned.statusCode(), assigned.body());

        String adminId = extract(send("GET", "/api/user/info", adminToken, null).body(), "\"userId\":(\\d+)");
        HttpResponse<String> escalation = send("PUT", "/api/user/" + adminId, adminToken,
                "{\"userName\":\"admin\",\"status\":\"1\",\"userRoles\":[\"R_ADMIN\",\"R_MENU_ADMIN\"]}");

        assertEquals(403, escalation.statusCode(), escalation.body());
        assertEquals(403, send("GET", "/api/menu/tree", adminToken, null).statusCode());
    }

    @Test
    void repeatedFailedLoginsLockTheUsername() throws Exception {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            assertEquals(401, attemptLogin("lockout-probe", "wrong-password").statusCode());
        }

        HttpResponse<String> locked = attemptLogin("lockout-probe", "wrong-password");

        assertEquals(429, locked.statusCode(), locked.body());
        assertTrue(locked.body().contains("\"code\":429"), locked.body());
    }
}
