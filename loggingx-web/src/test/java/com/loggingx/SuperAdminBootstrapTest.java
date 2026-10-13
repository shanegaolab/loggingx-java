package com.loggingx;

import com.loggingx.auth.service.AuthenticationService;
import com.loggingx.biz.user.UserService;
import com.loggingx.dao.account.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Without the {@code dev} profile there are no demo accounts; the first super administrator comes from
 * configuration, and self-registered users still get the default role.
 */
@SpringBootTest(properties = {
        "loggingx.security.jwt.secret=test-secret-that-is-at-least-32-bytes-long",
        "loggingx.bootstrap.super-admin.username=Root",
        "loggingx.bootstrap.super-admin.password=bootstrap-password-1"
})
class SuperAdminBootstrapTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void createsTheConfiguredSuperAdministratorOnly() {
        assertEquals(1, userAccountRepository.findAll().size());
        authenticationService.login("root", "bootstrap-password-1");
        assertEquals(List.of("R_SUPER"), userService.info("root").roles());

        authenticationService.register("alice", "password123");
        assertEquals(List.of(AuthenticationService.DEFAULT_ROLE_CODE), userService.info("alice").roles());
    }
}
