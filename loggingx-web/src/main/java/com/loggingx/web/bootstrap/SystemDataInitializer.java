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
        }
    }

    private void seed() {

        Long dashboard = directory("Dashboard", "/dashboard", "menus.dashboard.title", "ri:pie-chart-line", 1);
        Long console = page(dashboard, "Console", "console", "/dashboard/console", "menus.dashboard.console", 1,
                false, false, true, false);

        Long system = directory(SYSTEM_MENU, "/system", "menus.system.title", "ri:user-3-line", 2);
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

        Long exception = directory("Exception", "/exception", "menus.exception.title", "ri:error-warning-line", 3);
        Long forbidden = page(exception, "Exception403", "403", "/exception/403", "menus.exception.forbidden", 1,
                true, false, false, true);
        Long notFound = page(exception, "Exception404", "404", "/exception/404", "menus.exception.notFound", 2,
                true, false, false, true);
        Long serverError = page(exception, "Exception500", "500", "/exception/500",
                "menus.exception.serverError", 3, true, false, false, true);

        Set<Long> exceptionMenus = Set.of(exception, forbidden, notFound, serverError);
        Set<Long> adminMenus = new HashSet<>(Set.of(dashboard, console, system, user, userCenter, dept, post));
        adminMenus.addAll(exceptionMenus);
        Set<Long> userMenus = new HashSet<>(Set.of(dashboard, console));
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
