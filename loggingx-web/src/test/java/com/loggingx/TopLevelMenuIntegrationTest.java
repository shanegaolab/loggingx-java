package com.loggingx;

import com.loggingx.biz.menu.MenuService;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import com.loggingx.web.bootstrap.SystemDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Menus reorganised after a database was first seeded: backups moved to the top level, the single alert and AI pages
 * became directories. Startup migrates them, keeping their ids so roles granted them keep them.
 */
class TopLevelMenuIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private SystemDataInitializer initializer;

    @Autowired
    private MenuService menuService;

    @Autowired
    private RoleRepository roleRepository;

    private Menu menu(String name) {
        return menuRepository.findAll().stream().filter(menu -> name.equals(menu.name())).findFirst().orElseThrow();
    }

    /**
     * Earlier versions had one alert page (first in the monitoring directory, then at the top level); it becomes the
     * rules page of the alert center directory, and the roles that had it get the directory and the other pages.
     */
    @Test
    void theSingleAlertPageBecomesADirectory() throws Exception {
        Menu directory = menu("AlertCenter");
        Menu rules = menu("HostAlert");
        assertEquals(Menu.ROOT_PARENT_ID, directory.parentId(), "new databases seed the directory at the top level");
        assertEquals(directory.id(), rules.parentId());
        for (String name : new String[] {"AlertEvent", "AlertNotify"}) {
            menuService.delete(menu(name).id());
        }
        // As the first version seeded it: a page of the monitoring directory, granted to the admin role.
        Menu legacy = new Menu(rules.id(), menu("Monitor").id(), rules.type(), "HostAlert", "alert",
                "/monitor/alert", "menus.monitor.alert", null, 2, true, true, false, false, null, false, false, null,
                false, null, false, null, Instant.now());
        menuRepository.update(rules, legacy);
        menuService.delete(directory.id());
        Role admin = roleRepository.findAll().stream().filter(role -> "R_ADMIN".equals(role.code())).findFirst()
                .orElseThrow();
        assertTrue(admin.menuIds().contains(rules.id()));

        initializer.run(null);

        Menu newDirectory = menu("AlertCenter");
        Menu moved = menu("HostAlert");
        assertEquals(rules.id(), moved.id());
        assertEquals(newDirectory.id(), moved.parentId());
        assertEquals("/alert/rules", moved.component());
        assertEquals("menus.alert.rules", moved.title());
        Role upgraded = roleRepository.findById(admin.id()).orElseThrow();
        for (String name : new String[] {"AlertCenter", "AlertEvent", "AlertNotify"}) {
            assertTrue(upgraded.menuIds().contains(menu(name).id()), name + " is granted to the admin role");
        }
        String token = login("admin", DEMO_PASSWORD);
        String routes = send("GET", "/api/menu/routes", token, null).body();
        assertTrue(routes.contains("\"path\":\"/alert\"") && routes.contains("\"component\":\"/alert/events\"")
                && routes.contains("\"component\":\"/alert/notify\""), routes);
        assertEquals(200, send("GET", "/api/alert-center/log-alerts", token, null).statusCode());
        assertEquals(200, send("GET", "/api/alert-center/log-rules", token, null).statusCode());
        assertEquals(200, send("GET", "/api/alert-center/notify/records", token, null).statusCode());
    }

    /** Each alert center page grants its own endpoints: a role with only the events page cannot edit rules. */
    @Test
    void alertCenterPagesGrantTheirOwnEndpoints() throws Exception {
        Role user = roleRepository.findAll().stream().filter(role -> "R_USER".equals(role.code())).findFirst()
                .orElseThrow();
        roleRepository.modify(user.id(), current -> {
            Set<Long> ids = new HashSet<>(current.menuIds());
            ids.add(menu("AlertCenter").id());
            ids.add(menu("AlertEvent").id());
            return current.withMenuIds(ids, Instant.now());
        });
        try {
            // A user of its own: other tests change the demo user's password.
            HttpResponse<String> created = send("POST", "/api/user", login("super", DEMO_PASSWORD),
                    "{\"userName\":\"alert-viewer\",\"password\":\"password123\",\"userGender\":\"1\","
                            + "\"userRoles\":[\"R_USER\"]}");
            assertEquals(201, created.statusCode(), created.body());
            String token = login("alert-viewer", "password123");
            assertEquals(200, send("GET", "/api/alert-center/log-alerts", token, null).statusCode());
            assertEquals(200, send("GET", "/api/alert-center/log-rules", token, null).statusCode(),
                    "the events page opens the logs of a rule");
            assertEquals(200, send("GET", "/api/monitor/alerts", token, null).statusCode());
            assertEquals(403, send("POST", "/api/alert-center/log-rules", token, "{}").statusCode());
            assertEquals(403, send("DELETE", "/api/monitor/alert-rules/1", token, null).statusCode());
            assertEquals(403, send("GET", "/api/alert-center/notify/channels", token, null).statusCode());
            assertEquals(200, send("GET", "/api/alert-center/notify/records?eventType=log&eventId=1", token,
                    null).statusCode(), "an event's notification records");
            assertEquals(403, send("GET", "/api/alert-center/users", token, null).statusCode());
        } finally {
            roleRepository.modify(user.id(), current -> current.withMenuIds(user.menuIds(), Instant.now()));
        }
    }

    /**
     * Earlier versions had one backup page (first in the log directory, then at the top level); it becomes the
     * policies page of the backup directory, and the roles that had it get the archives page too.
     */
    @Test
    void theSingleBackupPageBecomesADirectory() throws Exception {
        Menu directory = menu("Backup");
        Menu policies = menu("LogBackup");
        assertEquals(Menu.ROOT_PARENT_ID, directory.parentId(), "new databases seed the directory at the top level");
        assertEquals(directory.id(), policies.parentId());
        menuService.delete(menu("BackupArchive").id());
        // As the first version seeded it: a page of the log directory, granted to the admin role.
        Menu legacy = new Menu(policies.id(), menu("Log").id(), policies.type(), "LogBackup", "backup",
                "/log/backup", "menus.log.backup", null, 5, true, true, false, false, null, false, false, null,
                false, null, false, null, Instant.now());
        menuRepository.update(policies, legacy);
        menuService.delete(directory.id());

        initializer.run(null);

        Menu moved = menu("LogBackup");
        assertEquals(policies.id(), moved.id());
        assertEquals(menu("Backup").id(), moved.parentId());
        assertEquals("/backup/policies", moved.component());
        Role admin = roleRepository.findAll().stream().filter(role -> "R_ADMIN".equals(role.code())).findFirst()
                .orElseThrow();
        for (String name : new String[] {"Backup", "BackupArchive"}) {
            assertTrue(admin.menuIds().contains(menu(name).id()), name + " is granted to the admin role");
        }
        String token = login("admin", DEMO_PASSWORD);
        String routes = send("GET", "/api/menu/routes", token, null).body();
        assertTrue(routes.contains("\"path\":\"/backup\"") && routes.contains("\"component\":\"/backup/archives\""),
                routes);
        assertEquals(200, send("GET", "/api/backup/storage", token, null).statusCode());
        assertEquals(200, send("GET", "/api/backup/archives", token, null).statusCode());
    }

    /**
     * Earlier versions had one top-level AI page; it becomes the diagnosis page of the AI directory, and the roles
     * that had it get the directory and the other AI pages.
     */
    @Test
    void theSingleAiPageBecomesADirectory() throws Exception {
        Menu directory = menu("AiOps");
        Menu diagnosis = menu("AiDiagnosis");
        assertEquals(directory.id(), diagnosis.parentId(), "new databases seed the directory");
        for (String name : new String[] {"AiChat", "AiErrorGroup", "AiKnowledge", "AiSettings"}) {
            menuService.delete(menu(name).id());
        }
        // As the previous version seeded it: a top-level page, granted to the admin role.
        Menu legacy = new Menu(diagnosis.id(), Menu.ROOT_PARENT_ID, diagnosis.type(), "AiDiagnosis", "/ai-diagnosis",
                "/ai-diagnosis/index", "menus.aiDiagnosis.title", "ri:robot-2-line", 4, true, true, false, false, null,
                false, false, null, false, null, false, null, Instant.now());
        menuRepository.update(diagnosis, legacy);
        menuService.delete(directory.id());
        Role admin = roleRepository.findAll().stream().filter(role -> "R_ADMIN".equals(role.code())).findFirst()
                .orElseThrow();
        assertTrue(admin.menuIds().contains(diagnosis.id()));

        initializer.run(null);

        Menu newDirectory = menu("AiOps");
        Menu moved = menu("AiDiagnosis");
        assertEquals(diagnosis.id(), moved.id());
        assertEquals(newDirectory.id(), moved.parentId());
        assertEquals("/ai/diagnosis", moved.component());
        Role upgraded = roleRepository.findById(admin.id()).orElseThrow();
        for (String name : new String[] {"AiOps", "AiChat", "AiErrorGroup", "AiKnowledge", "AiSettings"}) {
            assertTrue(upgraded.menuIds().contains(menu(name).id()), name + " is granted to the admin role");
        }
        String token = login("admin", DEMO_PASSWORD);
        assertEquals(200, send("GET", "/api/ai-ops/knowledge", token, null).statusCode());
        assertEquals(200, send("GET", "/api/ai-diagnosis/settings", token, null).statusCode());
        String routes = send("GET", "/api/menu/routes", token, null).body();
        assertTrue(routes.contains("\"path\":\"/ai\"") && routes.contains("\"component\":\"/ai/chat\""), routes);
    }
}
