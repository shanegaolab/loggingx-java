package com.loggingx.auth.service;

import com.loggingx.auth.model.AuthToken;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.TooManyRequestsException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService jwtTokenService;

    private final LoginAttemptLimiter loginAttemptLimiter = new LoginAttemptLimiter();

    private AuthenticationService newService() {
        return new AuthenticationService(userAccountRepository, roleRepository, passwordEncoder, jwtTokenService,
                loginAttemptLimiter);
    }

    private static UserAccount account(String username, String encodedPassword, String status) {
        return new UserAccount(1L, username, encodedPassword, 0, username, null, null, null, null, status,
                Set.of(), null, null, null, null);
    }

    @Test
    void registerNormalizesUsernameGrantsDefaultRoleAndIssuesToken() {
        AuthenticationService service = newService();
        when(roleRepository.findByCode(AuthenticationService.DEFAULT_ROLE_CODE))
                .thenReturn(Optional.of(new Role(3L, "R_USER", "User", null, true, Set.of(), null, null)));
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userAccountRepository.insert(any())).thenAnswer(invocation ->
                Optional.of(invocation.<UserAccount>getArgument(0).withId(7L)));
        when(jwtTokenService.issue(7L, 0)).thenReturn("access-token");
        when(jwtTokenService.expiresInSeconds()).thenReturn(7200L);

        AuthToken token = service.register(" Alice ", "password123");

        assertEquals("access-token", token.accessToken());
        assertEquals("Bearer", token.tokenType());
        assertEquals(7200L, token.expiresInSeconds());

        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).insert(accountCaptor.capture());
        assertEquals("alice", accountCaptor.getValue().username());
        assertEquals("encoded-password", accountCaptor.getValue().encodedPassword());
        assertEquals(Set.of(3L), accountCaptor.getValue().roleIds());
    }

    @Test
    void registerRejectsPasswordThatViolatesPolicy() {
        AuthenticationService service = newService();

        assertThrows(BadRequestException.class, () -> service.register("alice", "short"));
        verify(userAccountRepository, never()).insert(any());
    }

    @Test
    void registerRejectsUsernameWithWhitespace() {
        AuthenticationService service = newService();

        assertThrows(BadRequestException.class, () -> service.register("al ice", "password123"));
        verify(userAccountRepository, never()).insert(any());
    }

    @Test
    void loginLocksUsernameAfterRepeatedFailures() {
        AuthenticationService service = newService();
        when(userAccountRepository.findByUsername("alice"))
                .thenReturn(Optional.of(account("alice", "encoded-password", UserAccount.STATUS_ONLINE)));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            assertThrows(UnauthorizedException.class, () -> service.login("alice", "wrong-password"));
        }

        // Locked: even the right password is refused, and it is not checked at all.
        assertThrows(TooManyRequestsException.class, () -> service.login("Alice", "password123"));
        verify(passwordEncoder, never()).matches("password123", "encoded-password");
        verify(passwordEncoder, times(LoginAttemptLimiter.MAX_FAILURES))
                .matches("wrong-password", "encoded-password");
    }

    @Test
    void loginRejectsIncorrectPassword() {
        AuthenticationService service = newService();
        when(userAccountRepository.findByUsername("alice"))
                .thenReturn(Optional.of(account("alice", "encoded-password", UserAccount.STATUS_ONLINE)));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThrows(UnauthorizedException.class, () -> service.login("ALICE", "wrong-password"));
    }

    @Test
    void loginWithUnknownUsernameStillChecksPassword() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        AuthenticationService service = newService();
        when(userAccountRepository.findByUsername("bob")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("password123", "dummy-hash")).thenReturn(true);

        assertThrows(UnauthorizedException.class, () -> service.login("bob", "password123"));
        verify(passwordEncoder).matches("password123", "dummy-hash");
    }

    @Test
    void loginRejectsDeactivatedAccount() {
        AuthenticationService service = newService();
        when(userAccountRepository.findByUsername("carol"))
                .thenReturn(Optional.of(account("carol", "encoded-password", UserAccount.STATUS_DEACTIVATED)));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);

        assertThrows(UnauthorizedException.class, () -> service.login("carol", "password123"));
    }
}
