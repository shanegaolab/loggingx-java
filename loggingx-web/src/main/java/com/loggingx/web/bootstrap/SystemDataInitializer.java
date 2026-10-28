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
    private static final String MONITOR_MENU = "Monitor";
    private static final String HOST_MONITOR_MENU = "HostMonitor";

    /**
     * A page of a directory that earlier versions had as a single page. The first page listed keeps the name of that
     * single page, so its id, its grants and the permission checks naming it carry over.
     */
    private record DirectoryPage(String name, String path, String component, String title, int sort) {
    }

    /** A top-level directory with its pages; see {@link #addMissingDirectory(SplitDirectory)}. */
    private record SplitDirectory(String name, String path, String title, String icon, int sort,
                                  List<DirectoryPage> pages) {
    }

    /**
     * The alert center: events, rules and notification settings. The rules page is named after the single alert page
     * of earlier versions (which only had host alerts at first, in the monitoring directory).
     */
    private static final SplitDirectory ALERT_CENTER = new SplitDirectory("AlertCenter", "/alert",
            "menus.alert.title", "ri:alarm-warning-line", 4, List.of(
            new DirectoryPage("HostAlert", "rules", "/alert/rules", "menus.alert.rules", 2),
            new DirectoryPage("AlertEvent", "events", "/alert/events", "menus.alert.events", 1),
            new DirectoryPage("AlertNotify", "notify", "/alert/notify", "menus.alert.notify", 3)));

    /** AI ops, next to the alert center. The diagnosis page is named after the single AI page of earlier versions. */
    private static final SplitDirectory AI_OPS = new SplitDirectory("AiOps", "/ai", "menus.ai.title",
            "ri:robot-2-line", 4, List.of(
            new DirectoryPage("AiDiagnosis", "diagnosis", "/ai/diagnosis", "menus.ai.diagnosis", 2),
            new DirectoryPage("AiChat", "chat", "/ai/chat", "menus.ai.chat", 1),
            new DirectoryPage("AiErrorGroup", "error-groups", "/ai/error-group", "menus.ai.errorGroup", 3),
            new DirectoryPage("AiKnowledge", "knowledge", "/ai/knowledge", "menus.ai.knowledge", 4),
            new DirectoryPage("AiSettings", "settings", "/ai/settings", "menus.ai.settings", 5)));

    /**
     * Backups: the archive policies (storage, schedule, per store) and the archives. The policies page is named after
     * the single backup page of earlier versions (first in the log directory, then at the top level).
     */
    private static final SplitDirectory BACKUP = new SplitDirectory("Backup", "/backup", "menus.backup.title",
            "ri:archive-drawer-line", 5, List.of(
            new DirectoryPage("LogBackup", "policies", "/backup/policies", "menus.backup.policies", 1),
            new DirectoryPage("BackupArchive", "archives", "/backup/archives", "menus.backup.archives", 2)));

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
            addMissingDirectory(ALERT_CENTER);
            addMissingDirectory(BACKUP);
            addMissingDirectory(AI_OPS);
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
        Set<Long> alertPages = seedDirectory(ALERT_CENTER);
        Set<Long> backupPages = seedDirectory(BACKUP);
        Set<Long> aiPages = seedDirectory(AI_OPS);

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
                logs, logStore, logQuery, logTail, logAgent, monitor, hostMonitor));
        adminMenus.addAll(alertPages);
        adminMenus.addAll(backupPages);
        adminMenus.addAll(aiPages);
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
    }

    /** Creates a directory and its pages for a new database; returns their ids. */
    private Set<Long> seedDirectory(SplitDirectory spec) {
        Long directory = directory(spec.name(), spec.path(), spec.title(), spec.icon(), spec.sort());
        Set<Long> ids = new HashSet<>(Set.of(directory));
        spec.pages().forEach(page -> ids.add(page(directory, page)));
        return ids;
    }

    /**
     * Adds a directory and its pages to a database seeded before they existed. Earlier versions had the directory's
     * first page as a single page (at the top level or in another directory): it is moved into the directory, keeping
     * its id, and the roles that had it are granted the directory and the pages that took over its tabs. Pages added
     * without such a predecessor are only seen by the super administrator until granted, like
     * {@link #addMissingSystemPages()}.
     */
    private void addMissingDirectory(SplitDirectory spec) {
        List<Menu> menus = menuRepository.findAll().stream().filter(menu -> !menu.isButton()).toList();
        Set<String> names = menus.stream().map(Menu::name).collect(Collectors.toSet());
        Set<Long> added = new HashSet<>();
        Long directory = menus.stream().filter(menu -> spec.name().equals(menu.name())).map(Menu::id).findFirst()
                .orElseGet(() -> {
                    Long id = directory(spec.name(), spec.path(), spec.title(), spec.icon(), spec.sort());
                    added.add(id);
                    log.info("Added the {} menu", spec.name());
                    return id;
                });
        DirectoryPage first = spec.pages().getFirst();
        Optional<Menu> legacy = menus.stream().filter(menu -> first.name().equals(menu.name())).findFirst();
        if (legacy.isPresent() && !directory.equals(legacy.get().parentId())) {
            Menu page = legacy.get();
            menuRepository.update(page, new Menu(page.id(), directory, page.type(), page.name(), first.path(),
                    first.component(), first.title(), null, first.sort(), page.enabled(), true, page.hidden(),
                    page.hideTab(), page.link(), page.iframe(), page.showBadge(), page.textBadge(), page.fixedTab(),
                    page.activePath(), page.fullPage(), page.authMark(), Instant.now()));
            log.info("Moved the {} menu into the {} directory", first.name(), spec.name());
        }
        for (DirectoryPage page : spec.pages()) {
            if (!names.contains(page.name())) {
                added.add(page(directory, page));
                log.info("Added the {} menu", page.name());
            }
        }
        if (legacy.isPresent() && !added.isEmpty()) {
            Long legacyId = legacy.get().id();
            Instant now = Instant.now();
            roleRepository.findAll().stream()
                    .filter(role -> role.menuIds().contains(legacyId))
                    .forEach(role -> roleRepository.modify(role.id(), current -> {
                        Set<Long> ids = new HashSet<>(current.menuIds());
                        ids.add(directory);
                        ids.addAll(added);
                        return current.withMenuIds(ids, now);
                    }));
        }
    }

    private Long monitorDirectory() {
        return directory(MONITOR_MENU, "/monitor", "menus.monitor.title", "ri:pulse-line", 3);
    }

    private Long hostMonitorPage(Long monitor) {
        return page(monitor, HOST_MONITOR_MENU, "host", "/monitor/host", "menus.monitor.host", 1, true, false, false,
                false);
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

    private Long page(Long parentId, DirectoryPage page) {
        return page(parentId, page.name(), page.path(), page.component(), page.title(), page.sort(), true, false,
                false, false);
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
