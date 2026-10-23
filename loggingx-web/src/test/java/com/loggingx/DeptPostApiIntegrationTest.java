package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeptPostApiIntegrationTest extends ApiIntegrationTest {

    @Test
    void departmentsCanBeManagedAsATree() throws Exception {
        String token = login("super", DEMO_PASSWORD);

        HttpResponse<String> parent = send("POST", "/api/dept", token,
                "{\"deptName\":\"Integration HQ\",\"sort\":9,\"enabled\":true}");
        assertEquals(201, parent.statusCode(), parent.body());
        String parentId = extract(parent.body(), "\"deptId\":(\\d+)");

        HttpResponse<String> child = send("POST", "/api/dept", token,
                "{\"parentId\":" + parentId + ",\"deptName\":\"Integration team\",\"sort\":1,\"enabled\":true}");
        assertEquals(201, child.statusCode(), child.body());
        String childId = extract(child.body(), "\"deptId\":(\\d+)");

        HttpResponse<String> tree = send("GET", "/api/dept/tree?deptName=integration%20team", token, null);
        assertEquals(200, tree.statusCode(), tree.body());
        assertTrue(tree.body().contains("\"deptName\":\"Integration HQ\""), tree.body());
        assertTrue(tree.body().contains("\"deptName\":\"Integration team\""), tree.body());

        HttpResponse<String> deleteParent = send("DELETE", "/api/dept/" + parentId, token, null);
        assertEquals(400, deleteParent.statusCode(), deleteParent.body());
        assertTrue(deleteParent.body().contains("child departments"), deleteParent.body());
        HttpResponse<String> cycle = send("PUT", "/api/dept/" + parentId, token,
                "{\"parentId\":" + childId + ",\"deptName\":\"Integration HQ\",\"sort\":9,\"enabled\":true}");
        assertEquals(400, cycle.statusCode(), cycle.body());
        assertTrue(cycle.body().contains("below itself"), cycle.body());
        assertEquals(200, send("DELETE", "/api/dept/" + childId, token, null).statusCode());
        assertEquals(200, send("DELETE", "/api/dept/" + parentId, token, null).statusCode());
    }

    @Test
    void postsCanBeManaged() throws Exception {
        String token = login("super", DEMO_PASSWORD);

        HttpResponse<String> created = send("POST", "/api/post", token,
                "{\"postCode\":\"IT_QA\",\"postName\":\"Tester\",\"sort\":7,\"enabled\":true}");
        assertEquals(201, created.statusCode(), created.body());
        String postId = extract(created.body(), "\"postId\":(\\d+)");

        assertEquals(409, send("POST", "/api/post", token,
                "{\"postCode\":\"IT_QA\",\"postName\":\"Again\",\"enabled\":true}").statusCode());

        HttpResponse<String> updated = send("PUT", "/api/post/" + postId, token,
                "{\"postCode\":\"IT_QA\",\"postName\":\"QA engineer\",\"sort\":7,\"enabled\":false}");
        assertEquals(200, updated.statusCode(), updated.body());

        HttpResponse<String> list = send("GET", "/api/post/list?postCode=IT_QA", token, null);
        assertTrue(list.body().contains("\"postName\":\"QA engineer\""), list.body());
        assertTrue(list.body().contains("\"total\":1"), list.body());

        assertEquals(200, send("DELETE", "/api/post/" + postId, token, null).statusCode());
    }

    @Test
    void menusAreRequired() throws Exception {
        String token = login("user", DEMO_PASSWORD);

        assertEquals(403, send("GET", "/api/dept/tree", token, null).statusCode());
        assertEquals(403, send("GET", "/api/post/list", token, null).statusCode());
    }
}
