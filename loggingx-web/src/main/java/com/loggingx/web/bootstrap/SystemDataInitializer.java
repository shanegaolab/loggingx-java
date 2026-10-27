package com.loggingx.web.bootstrap;

import com.loggingx.auth.service.AuthenticationService;
import com.loggingx.biz.menu.MenuService;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seeds the menus and roles the application needs in every environment, when the store is empty.
 * <p>
 * The menus mirror the frontend's static route modules. Accounts are seeded separately: demo accounts by
 * {@link DemoDataInitializer} under the {@code dev} profile, the first super administrator by
 * {@link SuperAdminInitializer}.
 */
@Component
@Order(1)
public class SystemDataInitializer implements ApplicationRunner {

    public static final String ADMIN_ROLE_CODE = "R_ADMIN";
    public static final String USER_ROLE_CODE = AuthenticationService.DEFAULT_ROLE_CODE;

    private static final Logger log = LoggerFactory.getLogger(SystemDataInitializer.class);
    private static final String SYSTEM_MENU = "System";
    private static final String DEPT_MENU = "Dept";
    private static final String POST_MENU = "Post";
    private static final String LOG_MENU = "Log";
    private static final String LOG_STORE_MENU = "LogStore";
    private static final String LOG_QUERY_MENU = "LogQuery";
    private static final String LOG_TAIL_MENU = "LogTail";
    private static final String LOG_AGENT_MENU = "LogAgent";
    private static final String LOG_BACKUP_MENU = "LogBackup";
    private static final String MONITOR_MENU = "Monitor";
    private static final String HOST_MONITOR_MENU = "HostMonitor";
    /** The alert center; named after its first version, which only had host alerts. */
    private static final String HOST_ALERT_MENU = "HostAlert";
    private static final String ALERT_CENTER_PATH = "/alert";
    private static final String ALERT_CENTER_COMPONENT = "/alert/index";
    private static final String ALERT_CENTER_TITLE = "menus.alert.title";
    private static final String ALERT_CENTER_ICON = "ri:alarm-warning-line";
    private static final int ALERT_CENTER_SORT = 4;
    private static final String BACKUP_PATH = "/backup";
    private static final String BACKUP_COMPONENT = "/backup/index";
    private static final String BACKUP_TITLE = "menus.backup.title";
    private static final String BACKUP_ICON = "ri:archive-drawer-line";
    private static final int BACKUP_SORT = 5;

    private final MenuRepository menuRepository;
    private final RoleRepository roleRepository;

    public SystemDataInitializer(MenuRepository menuRepository, RoleRepository roleRepository) {
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (menuRepository.findAll().isEmpty()) {
            seed();
        } else {
            addMissingSystemPages();
            addMissingLogPages();
            addMissingMonitorPages();
        }
    }

    private void seed() {

        Long dashboard = directory("Dashboard", "/dashboard", "menus.dashboard.title", "ri:pie-chart-line", 1);
        Long console = page(dashboard, "Console", "console", "/dashboard/console", "menus.dashboard.console", 1,
                false, false, true, false);

        Long logs = logDirectory();
        Long logStore = logStorePage(logs);
        Long logQuery = logQueryPage(logs);
        Long logTail = logTailPage(logs);
        Long logAgent = logAgentPage(logs);

        Long monitor = monitorDirectory();
        Long hostMonitor = hostMonitorPage(monitor);
        Long hostAlert = alertCenterPage();
        Long logBackup = backupPage();

        Long system = directory(SYSTEM_MENU, "/system", "menus.system.title", "ri:user-3-line", 6);
        Long user = page(system, "User", "user", "/system/user", "menus.system.user", 1,
                true, false, false, false);
        page(system, "Role", "role", "/system/role", "menus.system.role", 2, true, false, false, false);
        Long userCenter = page(system, "UserCenter", "user-center", "/system/user-center",
                "menus.system.userCenter", 3, true, true, false, false);
        Long menus = page(system, "Menus", "menu", "/system/menu", "menus.system.menu", 4,
                true, false, false, false);
        button(menus, "新增", "add", 1);
        button(menus, "编辑", "edit", 2);
        button(menus, "删除", "delete", 3);
        Long dept = deptPage(system);
        Long post = postPage(system);

        Long exception = directory("Exception", "/exception", "menus.exception.title", "ri:error-warning-line", 7);
        Long forbidden = page(exception, "Exception403", "403", "/exception/403", "menus.exception.forbidden", 1,
                true, false, false, true);
        Long notFound = page(exception, "Exception404", "404", "/exception/404", "menus.exception.notFound", 2,
                true, false, false, true);
        Long serverError = page(exception, "Exception500", "500", "/exception/500",
                "menus.exception.serverError", 3, true, false, false, true);

        Set<Long> exceptionMenus = Set.of(exception, forbidden, notFound, serverError);
        Set<Long> adminMenus = new HashSet<>(Set.of(dashboard, console, system, user, userCenter, dept, post,
                logs, logStore, logQuery, logTail, logAgent, logBackup, monitor, hostMonitor, hostAlert));
        adminMenus.addAll(exceptionMenus);
        Set<Long> userMenus = new HashSet<>(Set.of(dashboard, console, logs, logQuery, logTail, monitor,
                hostMonitor));
        userMenus.addAll(exceptionMenus);

        // The super administrator role implicitly has every menu, so it needs no explicit grants.
        role(PermissionService.SUPER_ROLE_CODE, "超级管理员", "拥有系统全部权限", Set.of());
        role(ADMIN_ROLE_CODE, "管理员", "拥有系统管理权限", adminMenus);
        role(USER_ROLE_CODE, "普通用户", "拥有系统普通权限", userMenus);

        log.info("Seeded system data: {} menus, 3 roles", menuRepository.findAll().size());
    }

    /**
     * Adds pages introduced after a database was first seeded below the system directory. Only the super
     * administrator sees them until they are granted to other roles. A page is identified by its route name, so a
     * page deleted by an administrator comes back on the next start.
     */
    private void addMissingSystemPages() {
        List<Menu> menus = menuRepository.findAll();
        Set<String> names = menus.stream().filter(menu -> !menu.isButton()).map(Menu::name).collect(Collectors.toSet());
        Optional<Menu> system = menus.stream()
                .filter(menu -> !menu.isButton() && SYSTEM_MENU.equals(menu.name()))
                .findFirst();
        if (system.isEmpty()) {
            return;
        }
        if (!names.contains(DEPT_MENU)) {
            deptPage(system.get().id());
            log.info("Added the {} menu", DEPT_MENU);
        }
        if (!names.contains(POST_MENU)) {
            postPage(system.get().id());
            log.info("Added the {} menu", POST_MENU);
        }
    }

    /**
     * Adds the log management directory and pages to a database seeded before they existed, like
     * {@link #addMissingSystemPages()}: only the super administrator sees them until they are granted to other roles.
     */
    private void addMissingLogPages() {
        List<Menu> menus = menuRepository.findAll().stream().filter(menu -> !menu.isButton()).toList();
        Set<String> names = menus.stream().map(Menu::name).collect(Collectors.toSet());
        Long logs = menus.stream()
                .filter(menu -> LOG_MENU.equals(menu.name()))
                .map(Menu::id)
                .findFirst()
                .orElseGet(() -> {
                    log.info("Added the {} menu", LOG_MENU);
                    return logDirectory();
                });
        if (!names.contains(LOG_STORE_MENU)) {
            logStorePage(logs);
            log.info("Added the {} menu", LOG_STORE_MENU);
        }
        if (!names.contains(LOG_QUERY_MENU)) {
            logQueryPage(logs);
            log.info("Added the {} menu", LOG_QUERY_MENU);
        }
        if (!names.contains(LOG_TAIL_MENU)) {
            logTailPage(logs);
            log.info("Added the {} menu", LOG_TAIL_MENU);
        }
        if (!names.contains(LOG_AGENT_MENU)) {
            logAgentPage(logs);
            log.info("Added the {} menu", LOG_AGENT_MENU);
        }
        Optional<Menu> backup = menus.stream().filter(menu -> LOG_BACKUP_MENU.equals(menu.name())).findFirst();
        if (backup.isEmpty()) {
            backupPage();
            log.info("Added the {} menu", LOG_BACKUP_MENU);
        } else if (backup.get().parentId() != Menu.ROOT_PARENT_ID) {
            moveToTopLevel(backup.get(), BACKUP_PATH, BACKUP_COMPONENT, BACKUP_TITLE, BACKUP_ICON, BACKUP_SORT);
        }
    }

    /**
     * Adds the monitoring directory and pages to a database seeded before they existed, like
     * {@link #addMissingSystemPages()}: only the super administrator sees them until they are granted to other roles.
     */
    private void addMissingMonitorPages() {
        List<Menu> menus = menuRepository.findAll().stream().filter(menu -> !menu.isButton()).toList();
        Set<String> names = menus.stream().map(Menu::name).collect(Collectors.toSet());
        Long monitor = menus.stream()
                .filter(menu -> MONITOR_MENU.equals(menu.name()))
                .map(Menu::id)
                .findFirst()
                .orElseGet(() -> {
                    log.info("Added the {} menu", MONITOR_MENU);
                    return monitorDirectory();
                });
        if (!names.contains(HOST_MONITOR_MENU)) {
            hostMonitorPage(monitor);
            log.info("Added the {} menu", HOST_MONITOR_MENU);
        }
        Optional<Menu> alertCenter = menus.stream().filter(menu -> HOST_ALERT_MENU.equals(menu.name())).findFirst();
        if (alertCenter.isEmpty()) {
            alertCenterPage();
            log.info("Added the {} menu", HOST_ALERT_MENU);
        } else if (alertCenter.get().parentId() != Menu.ROOT_PARENT_ID) {
            moveToTopLevel(alertCenter.get(), ALERT_CENTER_PATH, ALERT_CENTER_COMPONENT, ALERT_CENTER_TITLE,
                    ALERT_CENTER_ICON, ALERT_CENTER_SORT);
        }
    }

    /**
     * Turns a page seeded below a directory by a previous version into a top-level menu (the alert center started in
     * the monitoring directory, backups in the log directory). The menu keeps its id and name, so roles granted it
     * and permission checks are unaffected.
     */
    private void moveToTopLevel(Menu page, String path, String component, String title, String icon, int sort) {
        Menu moved = new Menu(page.id(), Menu.ROOT_PARENT_ID, page.type(), page.name(), path, component, title, icon,
                sort, page.enabled(), page.keepAlive(), page.hidden(), page.hideTab(), page.link(), page.iframe(),
                page.showBadge(), page.textBadge(), page.fixedTab(), page.activePath(), page.fullPage(),
                page.authMark(), Instant.now());
        menuRepository.update(page, moved);
        log.info("Moved the {} menu to the top level", page.name());
    }

    private Long monitorDirectory() {
        return directory(MONITOR_MENU, "/monitor", "menus.monitor.title", "ri:pulse-line", 3);
    }

    private Long hostMonitorPage(Long monitor) {
        return page(monitor, HOST_MONITOR_MENU, "host", "/monitor/host", "menus.monitor.host", 1, true, false, false,
                false);
    }

    /** A top-level page (no directory): log and host alerts and their notifications. */
    private Long alertCenterPage() {
        return insert(new Menu(null, Menu.ROOT_PARENT_ID, MenuType.MENU, HOST_ALERT_MENU, ALERT_CENTER_PATH,
                ALERT_CENTER_COMPONENT, ALERT_CENTER_TITLE, ALERT_CENTER_ICON, ALERT_CENTER_SORT, true, true, false,
                false, null, false, false, null, false, null, false, null, Instant.now()));
    }

    private Long logDirectory() {
        return directory(LOG_MENU, "/log", "menus.log.title", "ri:file-list-3-line", 2);
    }

    private Long logStorePage(Long logs) {
        return page(logs, LOG_STORE_MENU, "store", "/log/store", "menus.log.store", 1, true, false, false, false);
    }

    private Long logQueryPage(Long logs) {
        return page(logs, LOG_QUERY_MENU, "query", "/log/query", "menus.log.query", 2, true, false, false, false);
    }

    // Not kept alive: leaving the page closes its live connection.
    private Long logTailPage(Long logs) {
        return page(logs, LOG_TAIL_MENU, "tail", "/log/tail", "menus.log.tail", 3, false, false, false, false);
    }

    private Long logAgentPage(Long logs) {
        return page(logs, LOG_AGENT_MENU, "agent", "/log/agent", "menus.log.agent", 4, true, false, false, false);
    }

    /** A top-level page (no directory): archives of the log stores in object storage. */
    private Long backupPage() {
        return insert(new Menu(null, Menu.ROOT_PARENT_ID, MenuType.MENU, LOG_BACKUP_MENU, BACKUP_PATH,
                BACKUP_COMPONENT, BACKUP_TITLE, BACKUP_ICON, BACKUP_SORT, true, true, false, false, null, false,
                false, null, false, null, false, null, Instant.now()));
    }

    private Long deptPage(Long system) {
        return page(system, DEPT_MENU, "dept", "/system/dept", "menus.system.dept", 5, true, false, false, false);
    }

    private Long postPage(Long system) {
        return page(system, POST_MENU, "post", "/system/post", "menus.system.post", 6, true, false, false, false);
    }

    private Long directory(String name, String path, String title, String icon, int sort) {
        return insert(new Menu(null, Menu.ROOT_PARENT_ID, MenuType.MENU, name, path, MenuService.LAYOUT_COMPONENT,
                title, icon, sort, true, false, false, false, null, false, false, null, false, null, false, null,
                Instant.now()));
    }

    private Long page(Long parentId, String name, String path, String component, String title, int sort,
                      boolean keepAlive, boolean hidden, boolean fixedTab, boolean fullPage) {
        // Hidden pages (user center) and full-screen pages (error pages) are not shown as tabs.
        boolean hideTab = hidden || fullPage;
        return insert(new Menu(null, parentId, MenuType.MENU, name, path, component, title, null, sort, true,
                keepAlive, hidden, hideTab, null, false, false, null, fixedTab, null, fullPage, null,
                Instant.now()));
    }

    private void button(Long parentId, String title, String authMark, int sort) {
        insert(new Menu(null, parentId, MenuType.BUTTON, null, null, null, title, null, sort, true, false, false,
                false, null, false, false, null, false, null, false, authMark, Instant.now()));
    }

    private Long insert(Menu menu) {
        return menuRepository.insert(menu).orElseThrow().id();
    }

    private void role(String code, String name, String description, Set<Long> menuIds) {
        Instant now = Instant.now();
        roleRepository.insert(new Role(null, code, name, description, true, menuIds, now, now)).orElseThrow();
    }
}
