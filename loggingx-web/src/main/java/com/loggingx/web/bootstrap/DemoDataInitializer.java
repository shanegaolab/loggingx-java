package com.loggingx.web.bootstrap;

import com.loggingx.biz.menu.MenuService;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Seeds the in-memory repositories with the menus, roles and demo accounts the frontend expects.
 * <p>
 * The menus mirror the frontend's static route modules. Demo accounts {@code super}, {@code admin} and
 * {@code user} all use the password {@value #DEMO_PASSWORD}. Disable with {@code loggingx.demo-data.enabled=false}
 * once a persistent repository is in place.
 */
@Component
@ConditionalOnProperty(name = "loggingx.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private static final String DEMO_PASSWORD = "123456";
    private static final String SYSTEM = "system";

    private final MenuRepository menuRepository;
    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(
            MenuRepository menuRepository,
            RoleRepository roleRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!menuRepository.findAll().isEmpty()) {
            return;
        }

        Long dashboard = directory("Dashboard", "/dashboard", "menus.dashboard.title", "ri:pie-chart-line", 1);
        Long console = page(dashboard, "Console", "console", "/dashboard/console", "menus.dashboard.console", 1,
                false, false, true, false);

        Long system = directory("System", "/system", "menus.system.title", "ri:user-3-line", 2);
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

        Long exception = directory("Exception", "/exception", "menus.exception.title", "ri:error-warning-line", 3);
        Long forbidden = page(exception, "Exception403", "403", "/exception/403", "menus.exception.forbidden", 1,
                true, false, false, true);
        Long notFound = page(exception, "Exception404", "404", "/exception/404", "menus.exception.notFound", 2,
                true, false, false, true);
        Long serverError = page(exception, "Exception500", "500", "/exception/500",
                "menus.exception.serverError", 3, true, false, false, true);

        Set<Long> exceptionMenus = Set.of(exception, forbidden, notFound, serverError);
        Set<Long> adminMenus = new HashSet<>(Set.of(dashboard, console, system, user, userCenter));
        adminMenus.addAll(exceptionMenus);
        Set<Long> userMenus = new HashSet<>(Set.of(dashboard, console));
        userMenus.addAll(exceptionMenus);

        // The super administrator role implicitly has every menu, so it needs no explicit grants.
        Long superRole = role(PermissionService.SUPER_ROLE_CODE, "超级管理员", "拥有系统全部权限", Set.of());
        Long adminRole = role("R_ADMIN", "管理员", "拥有系统管理权限", adminMenus);
        Long userRole = role("R_USER", "普通用户", "拥有系统普通权限", userMenus);

        account("super", "超级管理员", "1", "13800000001", "super@loggingx.local", superRole);
        account("admin", "管理员", "1", "13800000002", "admin@loggingx.local", adminRole);
        account("user", "普通用户", "2", "13800000003", "user@loggingx.local", userRole);

        log.info("Seeded demo data: {} menus, 3 roles, 3 accounts (password {})",
                menuRepository.findAll().size(), DEMO_PASSWORD);
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

    private Long role(String code, String name, String description, Set<Long> menuIds) {
        Instant now = Instant.now();
        return roleRepository.insert(new Role(null, code, name, description, true, menuIds, now, now))
                .orElseThrow().id();
    }

    private void account(String username, String nickName, String gender, String phone, String email, Long roleId) {
        Instant now = Instant.now();
        userAccountRepository.insert(new UserAccount(null, username, passwordEncoder.encode(DEMO_PASSWORD),
                nickName, gender, phone, email, null, UserAccount.STATUS_ONLINE, Set.of(roleId), now, SYSTEM,
                now, SYSTEM));
    }
}
