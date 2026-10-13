package com.loggingx.web.bootstrap;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Seeds the demo accounts the frontend expects, on top of the roles from {@link SystemDataInitializer}.
 * <p>
 * Demo accounts {@code super}, {@code admin} and {@code user} all use the password {@value #DEMO_PASSWORD}, which
 * is shorter than {@code PasswordPolicy} allows so that it matches the frontend's demo login; it is only ever
 * seeded under the {@code dev} profile.
 */
@Component
@Profile("dev")
@Order(2)
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private static final String DEMO_PASSWORD = "123456";
    private static final String SYSTEM = "system";

    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(
            RoleRepository roleRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.roleRepository = roleRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!userAccountRepository.findAll().isEmpty()) {
            return;
        }
        account("super", "超级管理员", "1", "13800000001", "super@loggingx.local", PermissionService.SUPER_ROLE_CODE);
        account("admin", "管理员", "1", "13800000002", "admin@loggingx.local", SystemDataInitializer.ADMIN_ROLE_CODE);
        account("user", "普通用户", "2", "13800000003", "user@loggingx.local", SystemDataInitializer.USER_ROLE_CODE);
        log.info("Seeded demo data: 3 accounts");
    }

    private void account(String username, String nickName, String gender, String phone, String email,
                         String roleCode) {
        Long roleId = roleRepository.findByCode(roleCode).map(Role::id).orElseThrow();
        Instant now = Instant.now();
        userAccountRepository.insert(new UserAccount(null, username, passwordEncoder.encode(DEMO_PASSWORD), 0,
                nickName, gender, phone, email, null, UserAccount.STATUS_ONLINE, Set.of(roleId), now, SYSTEM,
                now, SYSTEM));
    }
}
