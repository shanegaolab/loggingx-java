package com.loggingx.biz.role;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.dao.account.InMemoryUserAccountRepository;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.menu.InMemoryMenuRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.InMemoryRoleRepository;
import com.loggingx.dao.role.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleServiceTest {

    private final InMemoryMenuRepository menuRepository = new InMemoryMenuRepository();
    private final InMemoryRoleRepository roleRepository = new InMemoryRoleRepository();
    private final InMemoryUserAccountRepository userAccountRepository = new InMemoryUserAccountRepository();
    private final RoleService service = new RoleService(roleRepository, menuRepository, userAccountRepository,
            new PermissionService(userAccountRepository, roleRepository, menuRepository));

    private Long userMenu;
    private Long roleMenu;
    private Long menuMenu;

    @BeforeEach
    void setUp() {
        userMenu = menu("User");
        roleMenu = menu("Role");
        menuMenu = menu("Menus");
        Long adminRole = role("R_ADMIN", Set.of(userMenu, roleMenu));
        account("admin", adminRole);
    }

    private Long menu(String name) {
        return menuRepository.insert(new Menu(null, Menu.ROOT_PARENT_ID, MenuType.MENU, name, "/" + name,
                null, name, null, 0, true, false, false, false, null, false, false, null, false, null, false, null,
                Instant.now())).orElseThrow().id();
    }

    private Long role(String code, Set<Long> menuIds) {
        return roleRepository.insert(new Role(null, code, code, null, true, menuIds, Instant.now(), Instant.now()))
                .orElseThrow().id();
    }

    private Long account(String username, Long roleId) {
        return userAccountRepository.insert(new UserAccount(null, username, "encoded", 0, username, null, null,
                null, null, UserAccount.STATUS_ONLINE, Set.of(roleId), null, null, null, null)).orElseThrow().id();
    }

    @Test
    void assignMenusRejectsMenusTheOperatorDoesNotHold() {
        Long role = role("R_X", Set.of());

        assertThrows(ForbiddenException.class, () -> service.assignMenus(role, List.of(menuMenu), "admin"));
        assertEquals(List.of(), service.menuIdsOf(role));

        service.assignMenus(role, List.of(userMenu), "admin");
        assertEquals(List.of(userMenu), service.menuIdsOf(role));
    }

    @Test
    void rolesWithPermissionsTheOperatorDoesNotHoldCannotBeManaged() {
        Long bigger = role("R_BIG", Set.of(userMenu, roleMenu, menuMenu));

        assertThrows(ForbiddenException.class,
                () -> service.update(bigger, new RoleCommand("R_BIG", "Big", null, false), "admin"));
        assertThrows(ForbiddenException.class, () -> service.assignMenus(bigger, List.of(), "admin"));
        assertThrows(ForbiddenException.class, () -> service.delete(bigger, "admin"));
    }

    @Test
    void deleteRemovesTheRoleFromItsUsers() {
        Long role = role("R_X", Set.of(userMenu));
        Long alice = account("alice", role);

        service.delete(role, "admin");

        assertEquals(Set.of(), userAccountRepository.findById(alice).orElseThrow().roleIds());
    }
}
