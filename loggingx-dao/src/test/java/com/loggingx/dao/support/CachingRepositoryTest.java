package com.loggingx.dao.support;

import com.loggingx.dao.menu.CachingMenuRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.CachingRoleRepository;
import com.loggingx.dao.role.Role;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachingRepositoryTest {

    private static final TestRedis REDIS = new TestRedis();
    private static final Duration TTL = Duration.ofMinutes(10);

    private final TestDatabase database = new TestDatabase();
    private final CachingRoleRepository roles;
    private final CachingMenuRepository menus;

    CachingRepositoryTest() {
        REDIS.flushAll();
        roles = new CachingRoleRepository(database.roleRepository, REDIS.template, TTL);
        menus = new CachingMenuRepository(database.menuRepository, REDIS.template, TTL);
    }

    @AfterAll
    static void stopRedis() throws Exception {
        REDIS.close();
    }

    private static Role role(String code) {
        return new Role(null, code, code, "description", true, Set.of(1L, 2L), Instant.now(), Instant.now());
    }

    @Test
    void readsComeFromTheCacheUntilAWriteEvictsIt() {
        roles.insert(role("R_A"));
        assertEquals(1, roles.findAll().size());

        // Written behind the cache's back, so the cached table does not see it.
        database.roleRepository.insert(role("R_B"));
        assertEquals(1, roles.findAll().size());

        roles.insert(role("R_C"));
        assertEquals(3, roles.findAll().size());
        assertEquals("R_B", roles.findByCode("R_B").orElseThrow().code());
    }

    @Test
    void cachedEntitiesEqualStoredOnesSoTheyWorkAsUpdateSnapshots() {
        Long id = roles.insert(role("R_A")).orElseThrow().id();
        roles.findAll();
        Role cached = roles.findById(id).orElseThrow();
        assertEquals(database.roleRepository.findById(id).orElseThrow(), cached);

        assertEquals(UpdateResult.UPDATED, roles.update(cached, cached.withMenuIds(Set.of(3L))));
        assertEquals(Set.of(3L), roles.findById(id).orElseThrow().menuIds());
    }

    @Test
    void menusSurviveTheRoundTripThroughRedis() {
        menus.insert(new Menu(null, Menu.ROOT_PARENT_ID, MenuType.MENU, "User", "/user", "/system/user", "Users",
                "ri:user", 1, true, true, false, false, null, false, true, "new", false, null, false, null,
                Instant.now()));
        menus.insert(new Menu(null, 1L, MenuType.BUTTON, null, null, null, "Add", null, 1, true, false, false,
                false, null, false, false, null, false, null, false, "add", Instant.now()));

        menus.findAll();

        assertEquals(database.menuRepository.findAll(), menus.findAll());
    }
}
