package com.loggingx.biz.permission;

import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves which menus and buttons a user may access.
 * <p>
 * Access is the union of the menus assigned to the user's enabled roles, except for {@link #SUPER_ROLE_CODE},
 * which always has every menu so the system can never be locked out. Controllers reference this bean as
 * {@code @permissionService} in {@code @PreAuthorize} expressions.
 */
@Service("permissionService")
public class PermissionService {

    public static final String SUPER_ROLE_CODE = "R_SUPER";

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;

    public PermissionService(
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            MenuRepository menuRepository
    ) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
    }

    /**
     * @return whether the authenticated user can access at least one of the given menus (by route name)
     */
    public boolean hasAnyMenu(Authentication authentication, String... menuNames) {
        if (authentication == null) {
            return false;
        }
        Optional<UserAccount> account = userAccountRepository.findByUsername(authentication.getName());
        if (account.isEmpty()) {
            return false;
        }
        Set<Long> accessible = accessibleMenuIds(account.get());
        Set<String> wanted = Set.of(menuNames);
        return menuRepository.findAll().stream()
                .anyMatch(menu -> !menu.isButton() && menu.enabled()
                        && wanted.contains(menu.name()) && accessible.contains(menu.id()));
    }

    public List<Role> enabledRolesOf(UserAccount account) {
        return account.roleIds().stream()
                .map(roleRepository::findById)
                .flatMap(Optional::stream)
                .filter(Role::enabled)
                .toList();
    }

    public boolean isSuperAdmin(UserAccount account) {
        return enabledRolesOf(account).stream().anyMatch(role -> SUPER_ROLE_CODE.equals(role.code()));
    }

    /**
     * @return ids of every menu and button the user may access, ignoring whether the menu itself is enabled
     */
    public Set<Long> accessibleMenuIds(UserAccount account) {
        if (isSuperAdmin(account)) {
            return menuRepository.findAll().stream().map(Menu::id).collect(Collectors.toSet());
        }
        return enabledRolesOf(account).stream()
                .flatMap(role -> role.menuIds().stream())
                .collect(Collectors.toSet());
    }

    /**
     * @return button permission marks the user holds, e.g. {@code add}, {@code edit}
     */
    public List<String> buttonMarksOf(UserAccount account) {
        Set<Long> accessible = accessibleMenuIds(account);
        return menuRepository.findAll().stream()
                .filter(menu -> menu.isButton() && menu.enabled() && accessible.contains(menu.id()))
                .map(Menu::authMark)
                .distinct()
                .toList();
    }
}
