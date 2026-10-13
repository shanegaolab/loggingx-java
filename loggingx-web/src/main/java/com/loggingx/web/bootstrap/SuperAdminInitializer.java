package com.loggingx.web.bootstrap;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.UsernamePolicy;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Makes sure the system cannot start out locked: when no account holds the super administrator role, creates
 * one from {@code loggingx.bootstrap.super-admin.*}, or warns when no password is configured.
 * <p>
 * Runs after the other initializers, so the role exists and the {@code dev} demo accounts already count.
 */
@Component
@Order(3)
public class SuperAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminInitializer.class);
    private static final String SYSTEM = "system";

    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public SuperAdminInitializer(
            RoleRepository roleRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            @Value("${loggingx.bootstrap.super-admin.username:super}") String username,
            @Value("${loggingx.bootstrap.super-admin.password:}") String password
    ) {
        this.roleRepository = roleRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long superRoleId = roleRepository.findByCode(PermissionService.SUPER_ROLE_CODE).map(Role::id).orElseThrow(
                () -> new IllegalStateException("The super administrator role is missing"));
        boolean hasSuperAdmin = userAccountRepository.findAll().stream()
                .anyMatch(account -> account.roleIds().contains(superRoleId));
        if (hasSuperAdmin) {
            return;
        }
        if (password == null || password.isEmpty()) {
            log.warn("No super administrator exists; set LOGGINGX_BOOTSTRAP_SUPER_ADMIN_PASSWORD to create one");
            return;
        }

        String normalizedUsername = UsernamePolicy.requireValid(username);
        PasswordPolicy.requireValid(password);
        Instant now = Instant.now();
        userAccountRepository.insert(new UserAccount(null, normalizedUsername, passwordEncoder.encode(password), 0,
                        normalizedUsername, null, null, null, null, UserAccount.STATUS_ONLINE, Set.of(superRoleId),
                        now, SYSTEM, now, SYSTEM))
                .orElseThrow(() -> new IllegalStateException("Cannot create the super administrator: username '"
                        + normalizedUsername + "' is already taken"));
        log.info("Created super administrator '{}'; change its password after the first login", normalizedUsername);
    }
}
