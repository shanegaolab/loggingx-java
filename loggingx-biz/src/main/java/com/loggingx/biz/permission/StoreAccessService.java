package com.loggingx.biz.permission;

import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import com.loggingx.dao.role.RoleStoreScope;
import com.loggingx.dao.role.RoleStoreScopeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Which log stores a user may access: the union of the stores their enabled roles grant. A role grants every store
 * unless it was limited to some; the super administrator always has every store. Menus still decide which pages a
 * user sees; this decides which stores those pages show.
 */
@Service
public class StoreAccessService {

    private static final Logger log = LoggerFactory.getLogger(StoreAccessService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RoleStoreScopeMapper scopeMapper;
    private final RoleRepository roleRepository;
    private final UserAccountRepository userRepository;
    private final LogStoreRepository storeRepository;
    private final PermissionService permissionService;

    public StoreAccessService(RoleStoreScopeMapper scopeMapper, RoleRepository roleRepository,
                              UserAccountRepository userRepository, LogStoreRepository storeRepository,
                              PermissionService permissionService) {
        this.scopeMapper = scopeMapper;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.permissionService = permissionService;
    }

    /**
     * A role's stores.
     *
     * @param storeIds the stores when not {@code allStores}
     */
    public record Scope(boolean allStores, Set<Long> storeIds) {

        public static final Scope ALL = new Scope(true, Set.of());

        public Scope {
            storeIds = allStores || storeIds == null ? Set.of() : Set.copyOf(storeIds);
        }

        public boolean includes(Long storeId) {
            return allStores || storeIds.contains(storeId);
        }
    }

    // ---------- users ----------

    /**
     * @return the stores the user may access; {@link Scope#ALL} when unrestricted, an empty scope for an unknown user
     */
    public Scope scopeOf(String username) {
        Optional<UserAccount> account = username == null ? Optional.empty() : userRepository.findByUsername(username);
        if (account.isEmpty()) {
            return new Scope(false, Set.of());
        }
        if (permissionService.isSuperAdmin(account.get())) {
            return Scope.ALL;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Role role : permissionService.enabledRolesOf(account.get())) {
            Scope scope = roleScope(role.id());
            if (scope.allStores()) {
                return Scope.ALL;
            }
            ids.addAll(scope.storeIds());
        }
        return new Scope(false, ids);
    }

    public boolean canAccess(String username, Long storeId) {
        return storeId == null || scopeOf(username).includes(storeId);
    }

    /**
     * @throws ForbiddenException when the user may not access the store
     */
    public void require(String username, Long storeId) {
        if (!canAccess(username, storeId)) {
            throw new ForbiddenException("You have no access to this log store");
        }
    }

    /**
     * For changes that affect every store: creating or deleting a store (a store they could not see afterwards, or
     * one others rely on, is not theirs to add or remove) and alert rules for every store.
     *
     * @throws ForbiddenException when the user's roles are limited to some stores
     */
    public void requireAllStores(String username) {
        if (!scopeOf(username).allStores()) {
            throw new ForbiddenException("This needs access to every log store");
        }
    }

    /**
     * @return the items the user may see; items without a store ({@code storeId} is {@code null}, e.g. a rule for
     * every store) are kept
     */
    public <T> List<T> filter(String username, Collection<T> items, Function<T, Long> storeId) {
        Scope scope = scopeOf(username);
        if (scope.allStores()) {
            return List.copyOf(items);
        }
        return items.stream().filter(item -> {
            Long id = storeId.apply(item);
            return id == null || scope.includes(id);
        }).toList();
    }

    // ---------- roles ----------

    public Scope roleScope(Long roleId) {
        RoleStoreScope row = scopeMapper.selectScope(roleId);
        if (row == null || row.allStores()) {
            return Scope.ALL;
        }
        return new Scope(false, ids(row.storeIds()));
    }

    /**
     * Limits a role to some stores, or lets it access all. Administrators may only grant stores they may access
     * themselves.
     *
     * @throws ForbiddenException when the role is the super administrator or the operator lacks a granted store
     * @throws NotFoundException  when the role or a store does not exist
     */
    public Scope saveRoleScope(Long roleId, boolean allStores, Collection<Long> storeIds, String operatorUsername) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new NotFoundException("Role was not found"));
        if (PermissionService.SUPER_ROLE_CODE.equals(role.code())) {
            throw new ForbiddenException("The super administrator always has every log store");
        }
        Set<Long> ids = new LinkedHashSet<>(storeIds == null ? List.of() : storeIds);
        ids.remove(null);
        for (Long id : ids) {
            if (storeRepository.findById(id).isEmpty()) {
                throw new NotFoundException("Log store " + id + " was not found");
            }
        }
        Scope operator = scopeOf(operatorUsername);
        if (!operator.allStores() && (allStores || !operator.storeIds().containsAll(ids))) {
            throw new ForbiddenException("You can only grant log stores you have access to");
        }
        Scope scope = new Scope(allStores, ids);
        RoleStoreScope row = new RoleStoreScope(roleId, allStores, JSON.writeValueAsString(scope.storeIds()),
                Instant.now());
        if (scopeMapper.updateScope(row) == 0) {
            try {
                scopeMapper.insertScope(row);
            } catch (DuplicateKeyException e) {
                scopeMapper.updateScope(row);
            }
        }
        log.info("event=role.store-scope.update operator={} roleId={} allStores={} stores={}", operatorUsername,
                roleId, allStores, scope.storeIds());
        return scope;
    }

    private static Set<Long> ids(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(JSON.readValue(json, new TypeReference<List<Long>>() {
            }));
        } catch (JacksonException e) {
            return Set.of();
        }
    }
}
