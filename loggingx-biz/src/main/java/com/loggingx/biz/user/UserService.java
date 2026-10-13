package com.loggingx.biz.user;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.support.Filters;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.Usernames;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * User administration. Only a super administrator may grant the super administrator role or change a
 * super administrator account, and nobody can delete, deactivate or demote themselves.
 */
@Service
public class UserService {

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            PermissionService permissionService,
            PasswordEncoder passwordEncoder
    ) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.passwordEncoder = passwordEncoder;
    }

    public UserInfo info(String username) {
        UserAccount account = requireByUsername(username);
        return new UserInfo(
                account.id(),
                account.username(),
                account.nickName(),
                account.email(),
                account.avatar(),
                permissionService.enabledRolesOf(account).stream().map(Role::code).toList(),
                permissionService.buttonMarksOf(account)
        );
    }

    public PageResult<UserView> page(UserQuery query) {
        List<UserAccount> matches = userAccountRepository.findAll().stream()
                .filter(account -> Filters.containsIgnoreCase(account.username(), query.userName()))
                .filter(account -> Filters.containsIgnoreCase(account.phone(), query.userPhone()))
                .filter(account -> Filters.containsIgnoreCase(account.email(), query.userEmail()))
                .filter(account -> Filters.equalsIfPresent(account.gender(), query.userGender()))
                .filter(account -> Filters.equalsIfPresent(account.status(), query.status()))
                .toList();
        return PageResult.of(matches, query.current(), query.size()).map(this::toView);
    }

    public UserView create(UserCommand command, String operatorUsername) {
        UserAccount operator = requireByUsername(operatorUsername);
        Set<Long> roleIds = resolveRoleIds(command.roleCodes());
        if (grantsSuperRole(roleIds) && !permissionService.isSuperAdmin(operator)) {
            throw new ForbiddenException("Only a super administrator can grant the super administrator role");
        }
        PasswordPolicy.requireValid(command.password());

        String username = Usernames.normalize(command.username());
        Instant now = Instant.now();
        UserAccount account = new UserAccount(
                null,
                username,
                passwordEncoder.encode(command.password()),
                0,
                textOr(command.nickName(), username),
                command.gender(),
                trimToNull(command.phone()),
                trimToNull(command.email()),
                null,
                textOr(command.status(), UserAccount.STATUS_ONLINE),
                roleIds,
                now,
                operator.username(),
                now,
                operator.username()
        );
        return toView(userAccountRepository.insert(account).orElseThrow(() -> duplicateUsername(username)));
    }

    public UserView update(Long id, UserCommand command, String operatorUsername) {
        UserAccount operator = requireByUsername(operatorUsername);
        UserAccount existing = requireById(id);
        Set<Long> roleIds = resolveRoleIds(command.roleCodes());
        boolean operatorIsSuper = permissionService.isSuperAdmin(operator);
        if (!operatorIsSuper && (permissionService.isSuperAdmin(existing) || grantsSuperRole(roleIds))) {
            throw new ForbiddenException("Only a super administrator can manage super administrator accounts");
        }
        String status = textOr(command.status(), existing.status());
        if (existing.id().equals(operator.id())) {
            if (UserAccount.STATUS_DEACTIVATED.equals(status)) {
                throw new BadRequestException("You cannot deactivate your own account");
            }
            if (operatorIsSuper && !grantsSuperRole(roleIds)) {
                throw new BadRequestException("You cannot remove your own super administrator role");
            }
        }

        String password = command.password();
        String encodedPassword = existing.encodedPassword();
        boolean passwordChanged = password != null && !password.isBlank();
        if (passwordChanged) {
            PasswordPolicy.requireValid(password);
            encodedPassword = passwordEncoder.encode(password);
        }
        // Revoke outstanding tokens when the password changes or the account is deactivated, so that they do not
        // come back to life if the account is later reactivated.
        boolean deactivating = UserAccount.STATUS_DEACTIVATED.equals(status) && !existing.isDeactivated();
        int tokenVersion = passwordChanged || deactivating ? existing.tokenVersion() + 1 : existing.tokenVersion();

        String username = Usernames.normalize(command.username());
        UserAccount updated = new UserAccount(
                id,
                username,
                encodedPassword,
                tokenVersion,
                textOr(command.nickName(), username),
                command.gender(),
                trimToNull(command.phone()),
                trimToNull(command.email()),
                existing.avatar(),
                status,
                roleIds,
                existing.createdAt(),
                existing.createdBy(),
                Instant.now(),
                operator.username()
        );
        if (!userAccountRepository.update(updated)) {
            throw duplicateUsername(username);
        }
        return toView(updated);
    }

    public void delete(Long id, String operatorUsername) {
        UserAccount operator = requireByUsername(operatorUsername);
        UserAccount target = requireById(id);
        if (target.id().equals(operator.id())) {
            throw new BadRequestException("You cannot delete your own account");
        }
        if (permissionService.isSuperAdmin(target) && !permissionService.isSuperAdmin(operator)) {
            throw new ForbiddenException("Only a super administrator can delete a super administrator account");
        }
        userAccountRepository.deleteById(id);
    }

    private UserView toView(UserAccount account) {
        List<String> roleCodes = account.roleIds().stream()
                .map(roleRepository::findById)
                .flatMap(Optional::stream)
                .map(Role::code)
                .sorted()
                .toList();
        return new UserView(account.id(), account.username(), account.nickName(), account.gender(),
                account.phone(), account.email(), account.avatar(), account.status(), roleCodes,
                account.createdAt(), account.createdBy(), account.updatedAt(), account.updatedBy());
    }

    private Set<Long> resolveRoleIds(List<String> roleCodes) {
        Set<Long> roleIds = new HashSet<>();
        for (String code : roleCodes == null ? List.<String>of() : roleCodes) {
            Role role = roleRepository.findByCode(code)
                    .orElseThrow(() -> new BadRequestException("Role '" + code + "' does not exist"));
            roleIds.add(role.id());
        }
        return roleIds;
    }

    private boolean grantsSuperRole(Set<Long> roleIds) {
        return roleRepository.findByCode(PermissionService.SUPER_ROLE_CODE)
                .map(role -> roleIds.contains(role.id()))
                .orElse(false);
    }

    private UserAccount requireByUsername(String username) {
        return userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User account was not found"));
    }

    private UserAccount requireById(Long id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User account was not found"));
    }

    private static ConflictException duplicateUsername(String username) {
        return new ConflictException("Username '" + username + "' is already taken");
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
