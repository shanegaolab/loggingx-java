package com.loggingx.biz.role;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.support.Filters;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final UserAccountRepository userAccountRepository;

    public RoleService(
            RoleRepository roleRepository,
            MenuRepository menuRepository,
            UserAccountRepository userAccountRepository
    ) {
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.userAccountRepository = userAccountRepository;
    }

    public PageResult<Role> page(RoleQuery query) {
        List<Role> matches = roleRepository.findAll().stream()
                .filter(role -> Filters.containsIgnoreCase(role.name(), query.roleName()))
                .filter(role -> Filters.containsIgnoreCase(role.code(), query.roleCode()))
                .filter(role -> Filters.containsIgnoreCase(role.description(), query.description()))
                .filter(role -> Filters.equalsIfPresent(role.enabled(), query.enabled()))
                .filter(role -> createdWithin(role, query.startTime(), query.endTime()))
                .toList();
        return PageResult.of(matches, query.current(), query.size());
    }

    public List<Role> enabledRoles() {
        return roleRepository.findAll().stream().filter(Role::enabled).toList();
    }

    public Role create(RoleCommand command) {
        Instant now = Instant.now();
        Role role = new Role(null, command.code().trim(), command.name().trim(), trim(command.description()),
                command.enabled(), Set.of(), now, now);
        return roleRepository.insert(role).orElseThrow(() -> duplicateCode(role.code()));
    }

    public Role update(Long id, RoleCommand command) {
        Role existing = requireRole(id);
        String code = command.code().trim();
        if (isSuperRole(existing) && (!existing.code().equals(code) || !command.enabled())) {
            throw new BadRequestException("The super administrator role cannot be renamed or disabled");
        }
        Role updated = new Role(id, code, command.name().trim(), trim(command.description()), command.enabled(),
                existing.menuIds(), existing.createdAt(), Instant.now());
        if (!roleRepository.update(updated)) {
            throw duplicateCode(code);
        }
        return updated;
    }

    /**
     * Deletes the role and removes it from every user that had it.
     */
    public void delete(Long id) {
        Role role = requireRole(id);
        if (isSuperRole(role)) {
            throw new BadRequestException("The super administrator role cannot be deleted");
        }
        roleRepository.deleteById(id);
        for (UserAccount account : userAccountRepository.findAll()) {
            if (account.roleIds().contains(id)) {
                Set<Long> remaining = account.roleIds().stream()
                        .filter(roleId -> !roleId.equals(id))
                        .collect(Collectors.toSet());
                userAccountRepository.update(account.withRoleIds(remaining));
            }
        }
    }

    /**
     * @return ids of the menus and buttons granted to the role, in ascending order
     */
    public List<Long> menuIdsOf(Long id) {
        return requireRole(id).menuIds().stream().sorted().toList();
    }

    /**
     * Replaces the role's menus and buttons; unknown ids are ignored.
     */
    public void assignMenus(Long id, Collection<Long> menuIds) {
        Role role = requireRole(id);
        if (isSuperRole(role)) {
            // Saving would silently change nothing, since the super role always has every menu.
            throw new BadRequestException("The super administrator role always has every menu");
        }
        Set<Long> existingMenuIds = menuRepository.findAll().stream().map(Menu::id).collect(Collectors.toSet());
        Set<Long> granted = menuIds.stream().filter(existingMenuIds::contains).collect(Collectors.toSet());
        roleRepository.update(role.withMenuIds(granted, Instant.now()));
    }

    private Role requireRole(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role was not found"));
    }

    private static boolean isSuperRole(Role role) {
        return PermissionService.SUPER_ROLE_CODE.equals(role.code());
    }

    private static boolean createdWithin(Role role, LocalDate from, LocalDate to) {
        LocalDate createdOn = LocalDate.ofInstant(role.createdAt(), ZoneId.systemDefault());
        return (from == null || !createdOn.isBefore(from)) && (to == null || !createdOn.isAfter(to));
    }

    private static ConflictException duplicateCode(String code) {
        return new ConflictException("Role code '" + code + "' already exists");
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
