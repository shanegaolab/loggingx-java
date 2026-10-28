package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Roles can be limited to some log stores; users then see and query only those, on every page.
 */
class StoreAccessIntegrationTest extends ApiIntegrationTest {

    private String createStore(String token, String code) throws Exception {
        HttpResponse<String> created = send("POST", "/api/logstore", token,
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"retentionDays\":7,\"enabled\":true}");
        assertEquals(201, created.statusCode(), created.body());
        return extract(created.body(), "\"storeId\":(\\d+)");
    }

    private String roleId(String token, String code) throws Exception {
        return extract(send("GET", "/api/role/options", token, null).body(),
                "\"roleId\":(\\d+),\"roleName\":\"[^\"]*\",\"roleCode\":\"" + code + "\"");
    }

    @Test
    void usersOnlySeeTheStoresTheirRolesGrant() throws Exception {
        String superToken = login("super", DEMO_PASSWORD);
        String allowed = createStore(superToken, "it-scope-allowed");
        String hidden = createStore(superToken, "it-scope-hidden");
        String userRole = roleId(superToken, "R_USER");

        assertTrue(send("GET", "/api/role/" + userRole + "/log-stores", superToken, null).body()
                .contains("\"allStores\":true"), "roles start with every store");
        assertEquals(404, send("PUT", "/api/role/" + userRole + "/log-stores", superToken,
                "{\"allStores\":false,\"storeIds\":[999999]}").statusCode());
        assertEquals(403, send("PUT", "/api/role/" + roleId(superToken, "R_SUPER") + "/log-stores", superToken,
                "{\"allStores\":false,\"storeIds\":[" + allowed + "]}").statusCode());
        HttpResponse<String> limited = send("PUT", "/api/role/" + userRole + "/log-stores", superToken,
                "{\"allStores\":false,\"storeIds\":[" + allowed + "]}");
        assertEquals(200, limited.statusCode(), limited.body());
        assertTrue(limited.body().contains("\"storeIds\":[" + allowed + "]"), limited.body());
        try {
            String userToken = login("user", DEMO_PASSWORD);
            String options = send("GET", "/api/logstore/options", userToken, null).body();
            assertTrue(options.contains("it-scope-allowed"), options);
            assertFalse(options.contains("it-scope-hidden"), options);

            String range = "&startTime=2026-10-20T00:00:00Z&endTime=2026-10-21T00:00:00Z&current=1&size=10";
            assertEquals(200, send("GET", "/api/logs/search?storeId=" + allowed + range, userToken, null)
                    .statusCode());
            HttpResponse<String> refused = send("GET", "/api/logs/search?storeId=" + hidden + range, userToken,
                    null);
            assertEquals(403, refused.statusCode(), refused.body());
            assertEquals(403, send("GET", "/api/logs/tail?storeId=" + hidden, userToken, null).statusCode());
            assertEquals(403, send("GET", "/api/monitor/hosts/filesystems?storeId=" + hidden + "&host=h", userToken,
                    null).statusCode());
            assertEquals(200, send("GET", "/api/monitor/hosts", userToken, null).statusCode());

            // The super administrator is never limited.
            assertEquals(200, send("GET", "/api/logs/search?storeId=" + hidden + range, superToken, null)
                    .statusCode());
        } finally {
            assertEquals(200, send("PUT", "/api/role/" + userRole + "/log-stores", superToken,
                    "{\"allStores\":true}").statusCode());
        }
        assertEquals(200, send("GET", "/api/logs/search?storeId=" + hidden
                + "&startTime=2026-10-20T00:00:00Z&endTime=2026-10-21T00:00:00Z&current=1&size=10",
                login("user", DEMO_PASSWORD), null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + allowed, superToken, null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + hidden, superToken, null).statusCode());
    }
}
