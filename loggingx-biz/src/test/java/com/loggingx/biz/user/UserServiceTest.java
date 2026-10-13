package com.loggingx.biz.user;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import com.loggingx.dao.support.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Role USER_ROLE = new Role(3L, "R_USER", "User", null, true, Set.of(), null, null);

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userAccountRepository, roleRepository, permissionService, passwordEncoder);
        lenient().when(userAccountRepository.findByUsername("admin"))
                .thenReturn(Optional.of(account(1L, "admin", 0, UserAccount.STATUS_ONLINE)));
        lenient().when(roleRepository.findByCode("R_USER")).thenReturn(Optional.of(USER_ROLE));
        lenient().when(userAccountRepository.update(any(), any())).thenReturn(UpdateResult.UPDATED);
        lenient().when(permissionService.holdsAll(any(), any())).thenReturn(true);
    }

    private static UserAccount account(Long id, String username, int tokenVersion, String status) {
        return new UserAccount(id, username, "encoded", tokenVersion, username, null, null, null, null, status,
                Set.of(USER_ROLE.id()), null, null, null, null);
    }

    private static UserCommand command(String password, String status) {
        return new UserCommand("alice", password, null, null, null, null, status, List.of("R_USER"));
    }

    private int savedTokenVersion() {
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).update(any(), captor.capture());
        return captor.getValue().tokenVersion();
    }

    @Test
    void updateKeepsTokenVersionWhenPasswordIsBlank() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));

        service.update(7L, command("", "1"), "admin");

        assertEquals(2, savedTokenVersion());
    }

    @Test
    void updateRevokesTokensWhenPasswordChanges() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

        service.update(7L, command("new-password", "1"), "admin");

        assertEquals(3, savedTokenVersion());
    }

    @Test
    void updateRevokesTokensWhenAccountIsDeactivated() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));

        service.update(7L, command(null, UserAccount.STATUS_DEACTIVATED), "admin");

        assertEquals(3, savedTokenVersion());
    }

    @Test
    void updateRejectsPasswordThatViolatesPolicy() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));

        assertThrows(BadRequestException.class, () -> service.update(7L, command("short", "1"), "admin"));
        verify(userAccountRepository, never()).update(any(), any());
    }

    @Test
    void createRejectsPasswordThatViolatesPolicy() {
        assertThrows(BadRequestException.class, () -> service.create(command("123456", "1"), "admin"));
        verify(userAccountRepository, never()).insert(any());
    }

    @Test
    void updateRejectsGrantingPermissionsTheOperatorDoesNotHold() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));
        when(permissionService.menuIdsGrantedBy(Set.of(USER_ROLE.id()))).thenReturn(Set.of(10L, 11L));
        when(permissionService.holdsAll(any(), eq(Set.of(10L, 11L)))).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.update(7L, command(null, "1"), "admin"));
        verify(userAccountRepository, never()).update(any(), any());
    }

    @Test
    void updateReportsConflictWhenTheAccountChangedConcurrently() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));
        when(userAccountRepository.update(any(), any())).thenReturn(UpdateResult.STALE);

        assertThrows(ConflictException.class, () -> service.update(7L, command(null, "1"), "admin"));
    }

    @Test
    void createRejectsGrantingPermissionsTheOperatorDoesNotHold() {
        when(permissionService.menuIdsGrantedBy(Set.of(USER_ROLE.id()))).thenReturn(Set.of(10L));
        when(permissionService.holdsAll(any(), eq(Set.of(10L)))).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> service.create(command("password123", "1"), "admin"));
        verify(userAccountRepository, never()).insert(any());
    }
}
