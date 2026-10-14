package com.loggingx.dao.role;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisRoleRepository extends AbstractMybatisRepository<Role> implements RoleRepository {

    private final RoleMapper mapper;

    public MybatisRoleRepository(RoleMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    public Optional<Role> findByCode(String code) {
        return withMenus(mapper.selectByCode(code));
    }

    @Override
    protected Long idOf(Role role) {
        return role.id();
    }

    @Override
    protected Role withId(Role role, Long id) {
        return role.withId(id);
    }

    @Override
    protected List<Role> loadAll() {
        return attachLinks(mapper.selectAll(), true, mapper::selectMenuLinks, Role::withMenuIds);
    }

    @Override
    protected Optional<Role> load(Long id, boolean forUpdate) {
        return withMenus(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(Role role) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(role, key);
        syncMenus(key.getId(), Set.of(), role.menuIds());
        return key.getId();
    }

    @Override
    protected void updateRow(Role current, Role updated) {
        mapper.update(updated);
        syncMenus(updated.id(), current.menuIds(), updated.menuIds());
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }

    private Optional<Role> withMenus(Role role) {
        return Optional.ofNullable(role).map(found ->
                attachLinks(List.of(found), false, mapper::selectMenuLinks, Role::withMenuIds).getFirst());
    }

    private void syncMenus(Long id, Set<Long> current, Set<Long> updated) {
        syncLinks(id, current, updated, mapper::deleteMenuLinks, mapper::insertMenuLinks);
    }
}
