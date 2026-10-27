package com.loggingx;

import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.web.bootstrap.SystemDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alert center and backups are top-level menus; databases seeded when they were pages of the monitoring and log
 * directories get them moved on startup, keeping their ids so roles granted them keep them.
 */
class TopLevelMenuIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private SystemDataInitializer initializer;

    private Menu menu(String name) {
        return menuRepository.findAll().stream().filter(menu -> name.equals(menu.name())).findFirst().orElseThrow();
    }

    @Test
    void alertCenterIsMovedOutOfTheMonitoringDirectory() throws Exception {
        Menu alert = menu("HostAlert");
        assertEquals(Menu.ROOT_PARENT_ID, alert.parentId(), "new databases seed it at the top level");
        assertEquals("/alert", alert.path());

        // As seeded by the previous version.
        Menu old = new Menu(alert.id(), menu("Monitor").id(), alert.type(), alert.name(), "alert", "/monitor/alert",
                "menus.monitor.alert", null, 2, alert.enabled(), alert.keepAlive(), alert.hidden(), alert.hideTab(),
                alert.link(), alert.iframe(), alert.showBadge(), alert.textBadge(), alert.fixedTab(),
                alert.activePath(), alert.fullPage(), alert.authMark(), Instant.now());
        menuRepository.update(alert, old);

        initializer.run(null);

        Menu moved = menu("HostAlert");
        assertEquals(alert.id(), moved.id());
        assertEquals(Menu.ROOT_PARENT_ID, moved.parentId());
        assertEquals("/alert", moved.path());
        assertEquals("/alert/index", moved.component());
        assertEquals("menus.alert.title", moved.title());

        String token = login("admin", DEMO_PASSWORD);
        String routes = send("GET", "/api/menu/routes", token, null).body();
        assertTrue(routes.contains("\"path\":\"/alert\""), routes);
        assertEquals(200, send("GET", "/api/alert-center/stores", token, null).statusCode(),
                "admins keep the alert center");
    }

    @Test
    void backupsAreMovedOutOfTheLogDirectory() throws Exception {
        Menu backup = menu("LogBackup");
        assertEquals(Menu.ROOT_PARENT_ID, backup.parentId(), "new databases seed it at the top level");

        Menu old = new Menu(backup.id(), menu("Log").id(), backup.type(), backup.name(), "backup", "/log/backup",
                "menus.log.backup", null, 5, backup.enabled(), backup.keepAlive(), backup.hidden(),
                backup.hideTab(), backup.link(), backup.iframe(), backup.showBadge(), backup.textBadge(),
                backup.fixedTab(), backup.activePath(), backup.fullPage(), backup.authMark(), Instant.now());
        menuRepository.update(backup, old);

        initializer.run(null);

        Menu moved = menu("LogBackup");
        assertEquals(backup.id(), moved.id());
        assertEquals(Menu.ROOT_PARENT_ID, moved.parentId());
        assertEquals("/backup", moved.path());
        assertEquals("/backup/index", moved.component());
        assertEquals("menus.backup.title", moved.title());

        String token = login("admin", DEMO_PASSWORD);
        assertTrue(send("GET", "/api/menu/routes", token, null).body().contains("\"path\":\"/backup\""));
        assertEquals(200, send("GET", "/api/backup/status", token, null).statusCode(), "admins keep backups");
    }
}
