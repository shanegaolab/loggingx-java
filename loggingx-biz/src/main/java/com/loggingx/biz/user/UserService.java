package com.loggingx.biz.user;

import com.loggingx.biz.dept.DeptHierarchy;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.support.Filters;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.api.PageResult;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.Strings;
import com.loggingx.common.util.UsernamePolicy;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.dept.DeptRepository;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.post.PostRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * User administration. Only a super administrator may grant the super administrator role or change a
 * super administrator account, and nobody can delete, deactivate or demote themselves. Other administrators may
 * only grant roles, and manage users, whose permissions they hold themselves.
 * <p>
 * A user belongs to at most one department and may hold several posts. Disabled departments and posts cannot be
 * newly assigned, but a user keeps those they already have.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String USER_NOT_FOUND = "User account was not found";
    private static final Comparator<Post> POST_ORDER = Comparator.comparingInt(Post::sort).thenComparing(Post::id);

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final DeptRepository deptRepository;
    private final PostRepository postRepository;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            DeptRepository deptRepository,
            PostRepository postRepository,
            PermissionService permissionService,
            PasswordEncoder passwordEncoder
    ) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.deptRepository = deptRepository;
        this.postRepository = postRepository;
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
        List<Dept> depts = deptRepository.findAll();
        Set<Long> deptScope = query.deptId() == null ? null : DeptHierarchy.selfAndDescendants(query.deptId(), depts);
        List<UserAccount> matches = userAccountRepository.findAll().stream()
                .filter(account -> Filters.containsIgnoreCase(account.username(), query.userName()))
                .filter(account -> Filters.containsIgnoreCase(account.phone(), query.userPhone()))
                .filter(account -> Filters.containsIgnoreCase(account.email(), query.userEmail()))
                .filter(account -> Filters.equalsIfPresent(account.gender(), query.userGender()))
                .filter(account -> Filters.equalsIfPresent(account.status(), query.status()))
                .filter(account -> deptScope == null || deptScope.contains(account.deptId()))
                .toList();
        Names names = new Names(depts, postRepository.findAll());
        return PageResult.of(matches, query.current(), query.size()).map(account -> toView(account, names));
    }

    public UserView create(UserCommand command, String operatorUsername) {
        UserAccount operator = requireByUsername(operatorUsername);
        Set<Long> roleIds = resolveRoleIds(command.roleCodes());
        if (grantsSuperRole(roleIds) && !permissionService.isSuperAdmin(operator)) {
            throw new ForbiddenException("Only a super administrator can grant the super administrator role");
        }
        requireCanGrant(operator, roleIds);
        String username = UsernamePolicy.requireValid(command.username());
        PasswordPolicy.requireValid(command.password());
        Long deptId = resolveDeptId(command.deptId(), null);
        Set<Long> postIds = resolvePostIds(command.postIds(), deptId, null);

        Instant now = Instant.now();
        UserAccount account = new UserAccount(
                null,
                username,
                passwordEncoder.encode(command.password()),
                0,
                Strings.trimOr(command.nickName(), username),
                command.gender(),
                Strings.trimToNull(command.phone()),
                Strings.trimToNull(command.email()),
                null,
                Strings.trimOr(command.status(), UserAccount.STATUS_ONLINE),
                roleIds,
                deptId,
                postIds,
                now,
                operator.username(),
                now,
                operator.username()
        );
        UserAccount created = userAccountRepository.insert(account).orElseThrow(() -> duplicateUsername(username));
        log.info("event=user.create.success operator={} targetId={} targetUsername={}",
                operator.username(), created.id(), created.username());
        return toView(created);
    }

    public UserView update(Long id, UserCommand command, String operatorUsername) {
        UserAccount operator = requireByUsername(operatorUsername);
        UserAccount existing = requireById(id);
        Set<Long> roleIds = resolveRoleIds(command.roleCodes());
        boolean operatorIsSuper = permissionService.isSuperAdmin(operator);
        if (!operatorIsSuper && (permissionService.isSuperAdmin(existing) || grantsSuperRole(roleIds))) {
            throw new ForbiddenException("Only a super administrator can manage super administrator accounts");
        }
        requireCanManage(operator, existing);
        requireCanGrant(operator, roleIds);
        String status = Strings.trimOr(command.status(), existing.status());
        if (existing.id().equals(operator.id())) {
            if (UserAccount.STATUS_DEACTIVATED.equals(status)) {
                throw new BadRequestException("You cannot deactivate your own account");
            }
            if (operatorIsSuper && !grantsSuperRole(roleIds)) {
                throw new BadRequestException("You cannot remove your own super administrator role");
            }
        }
        String username = UsernamePolicy.requireValid(command.username());
        Long deptId = resolveDeptId(command.deptId(), existing.deptId());
        Set<Long> postIds = resolvePostIds(command.postIds(), deptId, existing);

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

        UserAccount updated = new UserAccount(
                id,
                username,
                encodedPassword,
                tokenVersion,
                Strings.trimOr(command.nickName(), username),
                command.gender(),
                Strings.trimToNull(command.phone()),
                Strings.trimToNull(command.email()),
                existing.avatar(),
                status,
                roleIds,
                deptId,
                postIds,
                existing.createdAt(),
                existing.createdBy(),
                Instant.now(),
                operator.username()
        );
        // Compare-and-set, so a concurrent edit (e.g. one that just revoked tokens) is never silently undone.
        Updates.require(userAccountRepository.update(existing, updated), USER_NOT_FOUND,
                () -> duplicateUsername(username));
        log.info("event=user.update.success operator={} targetId={} targetUsername={} passwordChanged={} status={}",
                operator.username(), updated.id(), updated.username(), passwordChanged, updated.status());
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
        requireCanManage(operator, target);
        userAccountRepository.deleteById(id);
        log.info("event=user.delete.success operator={} targetId={} targetUsername={}",
                operator.username(), target.id(), target.username());
    }

    private UserView toView(UserAccount account) {
        return toView(account, new Names(deptRepository.findAll(), postRepository.findAll()));
    }

    private UserView toView(UserAccount account, Names names) {
        List<String> roleCodes = account.roleIds().stream()
                .map(roleRepository::findById)
                .flatMap(Optional::stream)
                .map(Role::code)
                .sorted()
                .toList();
        Dept dept = account.deptId() == null ? null : names.depts().get(account.deptId());
        List<Post> posts = account.postIds().stream()
                .map(names.posts()::get)
                .filter(Objects::nonNull)
                .sorted(POST_ORDER)
                .toList();
        return new UserView(account.id(), account.username(), account.nickName(), account.gender(),
                account.phone(), account.email(), account.avatar(), account.status(), roleCodes,
                account.deptId(), dept == null ? null : dept.name(),
                posts.stream().map(Post::id).toList(), posts.stream().map(Post::name).toList(),
                account.createdAt(), account.createdBy(), account.updatedAt(), account.updatedBy());
    }

    /**
     * @param currentDeptId the user's current department, which may stay assigned even when disabled
     */
    private Long resolveDeptId(Long deptId, Long currentDeptId) {
        if (deptId == null) {
            return null;
        }
        Dept dept = deptRepository.findById(deptId)
                .orElseThrow(() -> new BadRequestException("Department was not found"));
        if (!dept.enabled() && !deptId.equals(currentDeptId)) {
            throw new BadRequestException("Department '" + dept.name() + "' is disabled");
        }
        return deptId;
    }

    /**
     * A post must be enabled and apply to the department {@code deptId} (or to one of its ancestors); a user without
     * a department can only hold posts that apply to every department. Posts the user already holds are kept even
     * when they no longer qualify, as long as the user stays in the same department.
     *
     * @param existing the user being updated, or {@code null} when creating one
     */
    private Set<Long> resolvePostIds(List<Long> postIds, Long deptId, UserAccount existing) {
        Set<Long> currentPostIds = existing == null ? Set.of() : existing.postIds();
        boolean sameDept = existing != null && Objects.equals(existing.deptId(), deptId);
        Set<Long> deptAndAncestors = null;
        Set<Long> resolved = new HashSet<>();
        for (Long postId : postIds == null ? List.<Long>of() : postIds) {
            Post post = postRepository.findById(postId)
                    .orElseThrow(() -> new BadRequestException("Post was not found"));
            if (!post.enabled() && !currentPostIds.contains(postId)) {
                throw new BadRequestException("Post '" + post.name() + "' is disabled");
            }
            if (!post.deptIds().isEmpty() && !(sameDept && currentPostIds.contains(postId))) {
                if (deptAndAncestors == null) {
                    deptAndAncestors = DeptHierarchy.selfAndAncestors(deptId, deptRepository.findAll());
                }
                if (!post.appliesTo(deptAndAncestors)) {
                    throw new BadRequestException("Post '" + post.name() + "' does not apply to the user's department");
                }
            }
            resolved.add(postId);
        }
        return resolved;
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

    private void requireCanGrant(UserAccount operator, Set<Long> roleIds) {
        if (!permissionService.holdsAll(operator, permissionService.menuIdsGrantedBy(roleIds))) {
            throw new ForbiddenException("You cannot grant a role with permissions you do not hold");
        }
    }

    private void requireCanManage(UserAccount operator, UserAccount target) {
        if (!permissionService.holdsAll(operator, permissionService.menuIdsGrantedBy(target.roleIds()))) {
            throw new ForbiddenException("You cannot manage a user who has permissions you do not hold");
        }
    }

    private boolean grantsSuperRole(Set<Long> roleIds) {
        return roleRepository.findByCode(PermissionService.SUPER_ROLE_CODE)
                .map(role -> roleIds.contains(role.id()))
                .orElse(false);
    }

    private UserAccount requireByUsername(String username) {
        return userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
    }

    private UserAccount requireById(Long id) {
        return userAccountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
    }

    private static ConflictException duplicateUsername(String username) {
        return new ConflictException("Username '" + username + "' is already taken");
    }

    /**
     * Departments and posts by id, loaded once for a batch of views.
     */
    private record Names(Map<Long, Dept> depts, Map<Long, Post> posts) {

        Names(List<Dept> depts, List<Post> posts) {
            this(depts.stream().collect(Collectors.toMap(Dept::id, Function.identity())),
                    posts.stream().collect(Collectors.toMap(Post::id, Function.identity())));
        }
    }
}
