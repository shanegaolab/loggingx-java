package com.loggingx.dao.support;

import com.loggingx.dao.account.MybatisUserAccountRepository;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.menu.MybatisMenuRepository;
import com.loggingx.dao.role.MybatisRoleRepository;
import com.loggingx.dao.role.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the repositories and their mappers against H2 in MySQL mode with the production schema.
 */
class MybatisRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-10-13T00:00:00Z");

    private final TestDatabase database = new TestDatabase();
    private final MybatisRoleRepository roleRepository = database.roleRepository;
    private final MybatisMenuRepository menuRepository = database.menuRepository;
    private final MybatisUserAccountRepository userAccountRepository = database.userAccountRepository;

    private Role insertRole(String code) {
        return roleRepository.insert(new Role(null, code, code, null, true, Set.of(), CREATED, CREATED)).orElseThrow();
    }

    private Menu menu(MenuType type, String name) {
        return new Menu(null, Menu.ROOT_PARENT_ID, type, name, "/" + name, null, "title", null, 1, true, false, false,
                false, null, false, false, null, false, null, false, null, Instant.now());
    }

    @Test
    void insertRoundTripsTheEntity() {
        Instant now = Instant.now();
        UserAccount account = new UserAccount(null, "alice", "hash", 0, "Alice", "2", null, "a@example.com", null,
                UserAccount.STATUS_ONLINE, Set.of(3L, 4L), now, "system", now, null);

        UserAccount saved = userAccountRepository.insert(account).orElseThrow();

        assertEquals(account.withId(saved.id()), saved);
        assertEquals(saved, userAccountRepository.findByUsername("alice").orElseThrow());
        assertTrue(userAccountRepository.findByUsername("ALICE").isEmpty());
        assertTrue(userAccountRepository.insert(account).isEmpty());
    }

    @Test
    void updateRefusesStaleSnapshot() {
        Role original = insertRole("R_A");
        Role first = original.withMenuIds(Set.of(1L), CREATED);
        Role second = original.withMenuIds(Set.of(2L), CREATED);

        assertEquals(UpdateResult.UPDATED, roleRepository.update(original, first));
        assertEquals(UpdateResult.STALE, roleRepository.update(original, second));
        assertEquals(Set.of(1L), roleRepository.findById(original.id()).orElseThrow().menuIds());
    }

    @Test
    void updateReportsMissingEntityAndDuplicateKey() {
        Role a = insertRole("R_A");
        insertRole("R_B");
        Role renamed = new Role(a.id(), "R_B", "A", null, true, Set.of(5L), CREATED, CREATED);

        assertEquals(UpdateResult.DUPLICATE_KEY, roleRepository.update(a, renamed));
        assertEquals(a, roleRepository.findById(a.id()).orElseThrow());
        roleRepository.deleteById(a.id());
        assertEquals(UpdateResult.NOT_FOUND, roleRepository.update(a, a));
    }

    @Test
    void modifyAppliesTheChangeToTheCurrentEntity() {
        Role original = insertRole("R_A");
        roleRepository.update(original, original.withMenuIds(Set.of(1L, 2L), CREATED));

        UpdateResult result = roleRepository.modify(original.id(), current -> current.withMenuIds(
                current.menuIds().stream().filter(id -> id != 1L).collect(Collectors.toSet()),
                CREATED));

        assertEquals(UpdateResult.UPDATED, result);
        assertEquals(Set.of(2L), roleRepository.findById(original.id()).orElseThrow().menuIds());
        assertEquals(UpdateResult.NOT_FOUND, roleRepository.modify(99L, current -> current));
    }

    @Test
    void routeNamesAreUniqueOnlyAmongMenus() {
        assertTrue(menuRepository.insert(menu(MenuType.MENU, "User")).isPresent());
        assertTrue(menuRepository.insert(menu(MenuType.MENU, "User")).isEmpty());
        assertTrue(menuRepository.insert(menu(MenuType.BUTTON, "User")).isPresent());
        assertTrue(menuRepository.insert(menu(MenuType.BUTTON, null)).isPresent());
        assertTrue(menuRepository.insert(menu(MenuType.BUTTON, null)).isPresent());

        List<Menu> menus = menuRepository.findAll();
        assertEquals(4, menus.size());
        assertTrue(menus.getFirst().id() < menus.getLast().id());
    }
}
