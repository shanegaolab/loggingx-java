package com.loggingx.biz.user;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.dept.DeptRepository;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.post.PostRepository;
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
    private DeptRepository deptRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userAccountRepository, roleRepository, deptRepository, postRepository,
                permissionService, passwordEncoder);
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

    private static UserAccount account(Long id, String username, Long deptId, Set<Long> postIds) {
        return new UserAccount(id, username, "encoded", 0, username, null, null, null, null, "1",
                Set.of(USER_ROLE.id()), deptId, postIds, null, null, null, null);
    }

    private static UserCommand command(String password, String status) {
        return command(password, status, null, null);
    }

    private static UserCommand command(String password, String status, Long deptId, List<Long> postIds) {
        return new UserCommand("alice", password, null, null, null, null, status, List.of("R_USER"), deptId, postIds);
    }

    private static Dept dept(Long id, Long parentId, String name, boolean enabled) {
        return new Dept(id, parentId, name, 0, null, null, null, enabled, null, null);
    }

    private static Post post(Long id, String name, int sort, boolean enabled) {
        return new Post(id, "P" + id, name, sort, enabled, null, null, null);
    }

    private static Post scopedPost(Long id, String name, Set<Long> deptIds) {
        return new Post(id, "P" + id, name, 0, true, null, deptIds, null, null);
    }

    private UserAccount savedAccount() {
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).update(any(), captor.capture());
        return captor.getValue();
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

    @Test
    void updateAssignsDepartmentAndPosts() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));
        when(deptRepository.findById(20L)).thenReturn(Optional.of(dept(20L, 0L, "R&D", true)));
        when(postRepository.findById(30L)).thenReturn(Optional.of(post(30L, "Developer", 1, true)));
        when(postRepository.findById(31L)).thenReturn(Optional.of(post(31L, "Lead", 0, true)));
        when(deptRepository.findAll()).thenReturn(List.of(dept(20L, 0L, "R&D", true)));
        when(postRepository.findAll()).thenReturn(List.of(post(30L, "Developer", 1, true), post(31L, "Lead", 0, true)));

        UserView view = service.update(7L, command(null, "1", 20L, List.of(30L, 31L)), "admin");

        assertEquals(20L, savedAccount().deptId());
        assertEquals(Set.of(30L, 31L), savedAccount().postIds());
        assertEquals("R&D", view.deptName());
        assertEquals(List.of(31L, 30L), view.postIds());
        assertEquals(List.of("Lead", "Developer"), view.postNames());
    }

    @Test
    void updateKeepsDisabledDepartmentAndPostsTheUserAlreadyHas() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 20L, Set.of(30L))));
        when(deptRepository.findById(20L)).thenReturn(Optional.of(dept(20L, 0L, "Closed", false)));
        when(postRepository.findById(30L)).thenReturn(Optional.of(post(30L, "Retired", 1, false)));

        service.update(7L, command(null, "1", 20L, List.of(30L)), "admin");

        assertEquals(20L, savedAccount().deptId());
        assertEquals(Set.of(30L), savedAccount().postIds());
    }

    @Test
    void updateRejectsNewlyAssigningDisabledDepartmentOrPost() {
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 2, "1")));
        when(deptRepository.findById(20L)).thenReturn(Optional.of(dept(20L, 0L, "Closed", false)));
        when(postRepository.findById(30L)).thenReturn(Optional.of(post(30L, "Retired", 1, false)));

        assertThrows(BadRequestException.class,
                () -> service.update(7L, command(null, "1", 20L, null), "admin"));
        assertThrows(BadRequestException.class,
                () -> service.update(7L, command(null, "1", null, List.of(30L)), "admin"));
        verify(userAccountRepository, never()).update(any(), any());
    }

    @Test
    void createRejectsUnknownDepartmentOrPost() {
        assertThrows(BadRequestException.class,
                () -> service.create(command("password123", "1", 99L, null), "admin"));
        assertThrows(BadRequestException.class,
                () -> service.create(command("password123", "1", null, List.of(99L)), "admin"));
        verify(userAccountRepository, never()).insert(any());
    }

    @Test
    void postsRestrictedToDepartmentsApplyToTheirDescendants() {
        // Company(1) > R&D(2) > Backend(3), Company(1) > Sales(4)
        lenient().when(deptRepository.findAll()).thenReturn(List.of(
                dept(1L, 0L, "Company", true), dept(2L, 1L, "R&D", true), dept(3L, 2L, "Backend", true),
                dept(4L, 1L, "Sales", true)));
        lenient().when(deptRepository.findById(3L)).thenReturn(Optional.of(dept(3L, 2L, "Backend", true)));
        lenient().when(deptRepository.findById(4L)).thenReturn(Optional.of(dept(4L, 1L, "Sales", true)));
        when(postRepository.findById(30L)).thenReturn(Optional.of(scopedPost(30L, "Engineer", Set.of(2L))));
        when(userAccountRepository.insert(any())).thenAnswer(call -> Optional.of(call.getArgument(0)));
        lenient().when(passwordEncoder.encode(any())).thenReturn("encoded");

        service.create(command("password123", "1", 3L, List.of(30L)), "admin");

        assertThrows(BadRequestException.class,
                () -> service.create(command("password123", "1", 4L, List.of(30L)), "admin"));
        assertThrows(BadRequestException.class,
                () -> service.create(command("password123", "1", null, List.of(30L)), "admin"));
    }

    @Test
    void updateKeepsAHeldPostOnlyWhileTheDepartmentIsUnchanged() {
        lenient().when(deptRepository.findAll()).thenReturn(List.of(
                dept(1L, 0L, "Company", true), dept(2L, 1L, "R&D", true), dept(4L, 1L, "Sales", true)));
        when(deptRepository.findById(4L)).thenReturn(Optional.of(dept(4L, 1L, "Sales", true)));
        // The post was narrowed to R&D after the Sales user got it.
        when(postRepository.findById(30L)).thenReturn(Optional.of(scopedPost(30L, "Engineer", Set.of(2L))));
        when(userAccountRepository.findById(7L)).thenReturn(Optional.of(account(7L, "alice", 4L, Set.of(30L))));

        service.update(7L, command(null, "1", 4L, List.of(30L)), "admin");
        assertEquals(Set.of(30L), savedAccount().postIds());

        assertThrows(BadRequestException.class,
                () -> service.update(7L, command(null, "1", null, List.of(30L)), "admin"));
    }

    @Test
    void pageFiltersByDepartmentIncludingDescendants() {
        when(deptRepository.findAll()).thenReturn(List.of(
                dept(1L, 0L, "Company", true), dept(2L, 1L, "R&D", true), dept(3L, 2L, "Backend", true),
                dept(4L, 1L, "Sales", true)));
        when(userAccountRepository.findAll()).thenReturn(List.of(
                account(10L, "ceo", 1L, Set.of()), account(11L, "dev", 3L, Set.of()),
                account(12L, "seller", 4L, Set.of()), account(13L, "loner", null, Set.of())));

        List<String> rnd = service.page(new UserQuery(1, 10, null, null, null, null, null, 2L)).records().stream()
                .map(UserView::username).toList();
        List<String> all = service.page(new UserQuery(1, 10, null, null, null, null, null, null)).records().stream()
                .map(UserView::username).toList();

        assertEquals(List.of("dev"), rnd);
        assertEquals(List.of("ceo", "dev", "seller", "loner"), all);
    }
}
