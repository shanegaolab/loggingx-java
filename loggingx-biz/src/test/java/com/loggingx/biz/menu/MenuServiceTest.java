package com.loggingx.biz.menu;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.RoleRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.support.TestDatabase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MenuServiceTest {

    private final TestDatabase database = new TestDatabase();
    private final MenuRepository menuRepository = database.menuRepository;
    private final RoleRepository roleRepository = database.roleRepository;
    private final UserAccountRepository userAccountRepository = database.userAccountRepository;
    private final MenuService service = new MenuService(menuRepository, roleRepository, userAccountRepository,
            new PermissionService(userAccountRepository, roleRepository, menuRepository));

    private static MenuCommand page(String name) {
        return new MenuCommand(MenuType.MENU, null, name, name, "/" + name, null, null, 0, true, false, false,
                false, null, false, false, null, false, null, false, null);
    }

    private static MenuCommand button(Long parentId, String authMark) {
        return new MenuCommand(MenuType.BUTTON, parentId, "Button", null, null, null, null, 0, true, false, false,
                false, null, false, false, null, false, null, false, authMark);
    }

    @Test
    void authMarkUniquenessIgnoresSurroundingWhitespace() {
        Long parent = service.create(page("Menus")).id();
        service.create(button(parent, "add"));

        assertThrows(ConflictException.class, () -> service.create(button(parent, " add ")));
    }

    @Test
    void duplicateRouteNameIsAConflict() {
        service.create(page("Menus"));

        assertThrows(ConflictException.class, () -> service.create(page(" Menus ")));
    }

    @Test
    void deleteRevokesTheSubtreeFromRoles() {
        Long parent = service.create(page("Menus")).id();
        Long add = service.create(button(parent, "add")).id();
        Long other = service.create(page("Other")).id();
        Long role = roleRepository.insert(new Role(null, "R_X", "X", null, true, Set.of(parent, add, other),
                Instant.now(), Instant.now())).orElseThrow().id();

        service.delete(parent);

        assertEquals(Set.of(other), roleRepository.findById(role).orElseThrow().menuIds());
        assertEquals(Set.of(other), Set.copyOf(menuRepository.findAll().stream().map(Menu::id).toList()));
    }
}
