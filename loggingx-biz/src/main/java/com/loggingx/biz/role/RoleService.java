package com.loggingx.biz.role;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.support.Filters;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Role administration. Administrators may only change roles, and grant menus, whose permissions they hold
 * themselves, so that role management cannot be used to escalate privileges.
 */
@Service
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);
    private static final String ROLE_NOT_FOUND = "Role was not found";

    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final UserAccountRepository userAccountRepository;
    private final PermissionService permissionService;

    public RoleService(
            RoleRepository roleRepository,
            MenuRepository menuRepository,
            UserAccountRepository userAccountRepository,
            PermissionService permissionService
    ) {
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.userAccountRepository = userAccountRepository;
        this.permissionService = permissionService;
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
        Role role = new Role(null, command.code().trim(), command.name().trim(),
                trim(command.description()), command.enabled(), Set.of(), now, now);
        Role created = roleRepository.insert(role).orElseThrow(() -> duplicateCode(role.code()));
        log.info("event=role.create.success roleId={} roleCode={}", created.id(), created.code());
        return created;
    }

    public Role update(Long id, RoleCommand command, String operatorUsername) {
        Role existing = requireRole(id);
        requireCanManage(requireOperator(operatorUsername), existing);
        String code = command.code().trim();
        if (isSuperRole(existing) && (!existing.code().equals(code) || !command.enabled())) {
            throw new BadRequestException("The super administrator role cannot be renamed or disabled");
        }
        Role updated = new Role(id, code, command.name().trim(), trim(command.description()),
                command.enabled(), existing.menuIds(), existing.createdAt(), Instant.now());
        Updates.require(roleRepository.update(existing, updated), ROLE_NOT_FOUND, () -> duplicateCode(code));
        log.info("event=role.update.success operator={} roleId={} roleCode={} enabled={}",
                operatorUsername, updated.id(), updated.code(), updated.enabled());
        return updated;
    }

    /**
     * Deletes the role and removes it from every user that had it.
     */
    public void delete(Long id, String operatorUsername) {
        Role role = requireRole(id);
        if (isSuperRole(role)) {
            throw new BadRequestException("The super administrator role cannot be deleted");
        }
        requireCanManage(requireOperator(operatorUsername), role);
        roleRepository.deleteById(id);
        for (UserAccount account : userAccountRepository.findAll()) {
            if (account.roleIds().contains(id)) {
                // Applied to the current account, so a concurrent edit of the user is not overwritten.
                userAccountRepository.modify(account.id(), current -> current.withRoleIds(current.roleIds().stream()
                        .filter(roleId -> !roleId.equals(id))
                        .collect(Collectors.toSet())));
            }
        }
        log.info("event=role.delete.success operator={} roleId={} roleCode={}",
                operatorUsername, role.id(), role.code());
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
    public void assignMenus(Long id, Collection<Long> menuIds, String operatorUsername) {
        Role role = requireRole(id);
        if (isSuperRole(role)) {
            // Saving would silently change nothing, since the super role always has every menu.
            throw new BadRequestException("The super administrator role always has every menu");
        }
        UserAccount operator = requireOperator(operatorUsername);
        requireCanManage(operator, role);
        Set<Long> existingMenuIds = menuRepository.findAll().stream().map(Menu::id).collect(Collectors.toSet());
        Set<Long> granted = menuIds.stream().filter(existingMenuIds::contains).collect(Collectors.toSet());
        if (!permissionService.holdsAll(operator, granted)) {
            throw new ForbiddenException("You cannot grant menus you do not have access to");
        }
        Updates.require(roleRepository.update(role, role.withMenuIds(granted, Instant.now())), ROLE_NOT_FOUND,
                () -> duplicateCode(role.code()));
        log.info("event=role.menus.update.success operator={} roleId={} roleCode={} menuCount={}",
                operatorUsername, role.id(), role.code(), granted.size());
    }

    private void requireCanManage(UserAccount operator, Role role) {
        if (!permissionService.holdsAll(operator, permissionService.menuIdsGrantedBy(Set.of(role.id())))) {
            throw new ForbiddenException("You cannot manage a role with permissions you do not hold");
        }
    }

    private UserAccount requireOperator(String username) {
        return userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User account was not found"));
    }

    private Role requireRole(Long id) {
        return roleRepository.findById(id).orElseThrow(() -> new NotFoundException(ROLE_NOT_FOUND));
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
