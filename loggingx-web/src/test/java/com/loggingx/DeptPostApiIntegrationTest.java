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
    void usersCanBeAssignedToDepartmentsAndPosts() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String parentId = extract(send("POST", "/api/dept", token,
                "{\"deptName\":\"Assignment HQ\",\"sort\":1,\"enabled\":true}").body(), "\"deptId\":(\\d+)");
        String childId = extract(send("POST", "/api/dept", token,
                "{\"parentId\":" + parentId + ",\"deptName\":\"Assignment team\",\"sort\":1,\"enabled\":true}").body(),
                "\"deptId\":(\\d+)");
        // Restricted to the parent department, so it also applies to the child department.
        HttpResponse<String> post = send("POST", "/api/post", token,
                "{\"postCode\":\"IT_ASSIGN\",\"postName\":\"Assignee\",\"sort\":1,\"enabled\":true,"
                        + "\"deptIds\":[" + parentId + "]}");
        assertEquals(201, post.statusCode(), post.body());
        assertTrue(post.body().contains("\"deptNames\":[\"Assignment HQ\"]"), post.body());
        String postId = extract(post.body(), "\"postId\":(\\d+)");

        HttpResponse<String> outside = send("POST", "/api/user", token,
                "{\"userName\":\"outsider\",\"password\":\"password123\",\"userGender\":\"1\",\"userRoles\":[\"R_USER\"],"
                        + "\"postIds\":[" + postId + "]}");
        assertEquals(400, outside.statusCode(), outside.body());
        assertTrue(outside.body().contains("does not apply"), outside.body());

        HttpResponse<String> created = send("POST", "/api/user", token,
                "{\"userName\":\"assignee\",\"password\":\"password123\",\"userGender\":\"1\",\"userRoles\":[\"R_USER\"],"
                        + "\"deptId\":" + childId + ",\"postIds\":[" + postId + "]}");
        assertEquals(201, created.statusCode(), created.body());
        String userId = extract(created.body(), "\"id\":(\\d+)");
        assertTrue(created.body().contains("\"deptName\":\"Assignment team\""), created.body());
        assertTrue(created.body().contains("\"postNames\":[\"Assignee\"]"), created.body());

        // Filtering by a department includes the users of its sub-departments.
        HttpResponse<String> byParent = send("GET", "/api/user/list?deptId=" + parentId, token, null);
        assertEquals(200, byParent.statusCode(), byParent.body());
        assertTrue(byParent.body().contains("\"userName\":\"assignee\""), byParent.body());
        assertTrue(byParent.body().contains("\"total\":1"), byParent.body());

        HttpResponse<String> deleteDept = send("DELETE", "/api/dept/" + childId, token, null);
        assertEquals(400, deleteDept.statusCode(), deleteDept.body());
        assertTrue(deleteDept.body().contains("users"), deleteDept.body());
        HttpResponse<String> deletePost = send("DELETE", "/api/post/" + postId, token, null);
        assertEquals(400, deletePost.statusCode(), deletePost.body());

        HttpResponse<String> options = send("GET", "/api/dept/options", token, null);
        assertEquals(200, options.statusCode(), options.body());
        assertTrue(options.body().contains("\"deptName\":\"Assignment team\""), options.body());
        assertTrue(send("GET", "/api/post/options", token, null).body().contains("\"postCode\":\"IT_ASSIGN\""));

        assertEquals(200, send("DELETE", "/api/user/" + userId, token, null).statusCode());
        assertEquals(200, send("DELETE", "/api/dept/" + childId, token, null).statusCode());
        // The post is still restricted to the parent department.
        HttpResponse<String> deleteParentDept = send("DELETE", "/api/dept/" + parentId, token, null);
        assertEquals(400, deleteParentDept.statusCode(), deleteParentDept.body());
        assertTrue(deleteParentDept.body().contains("posts"), deleteParentDept.body());
        assertEquals(200, send("DELETE", "/api/post/" + postId, token, null).statusCode());
        assertEquals(200, send("DELETE", "/api/dept/" + parentId, token, null).statusCode());
    }

    @Test
    void menusAreRequired() throws Exception {
        String token = login("user", DEMO_PASSWORD);

        assertEquals(403, send("GET", "/api/dept/tree", token, null).statusCode());
        assertEquals(403, send("GET", "/api/post/list", token, null).statusCode());
        assertEquals(403, send("GET", "/api/dept/options", token, null).statusCode());
        assertEquals(403, send("GET", "/api/post/options", token, null).statusCode());
    }
}
