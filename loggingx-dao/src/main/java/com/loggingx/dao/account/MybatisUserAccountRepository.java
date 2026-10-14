package com.loggingx.dao.account;

import com.loggingx.dao.support.AbstractMybatisRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisUserAccountRepository extends AbstractMybatisRepository<UserAccount>
        implements UserAccountRepository {

    private final UserAccountMapper mapper;

    public MybatisUserAccountRepository(UserAccountMapper mapper, PlatformTransactionManager transactionManager) {
        super(transactionManager);
        this.mapper = mapper;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return withRoles(mapper.selectByUsername(username));
    }

    @Override
    protected Long idOf(UserAccount account) {
        return account.id();
    }

    @Override
    protected UserAccount withId(UserAccount account, Long id) {
        return account.withId(id);
    }

    @Override
    protected List<UserAccount> loadAll() {
        return attachLinks(mapper.selectAll(), true, mapper::selectRoleLinks, UserAccount::withRoleIds);
    }

    @Override
    protected Optional<UserAccount> load(Long id, boolean forUpdate) {
        return withRoles(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(UserAccount account) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(account, key);
        syncRoles(key.getId(), Set.of(), account.roleIds());
        return key.getId();
    }

    @Override
    protected void updateRow(UserAccount current, UserAccount updated) {
        mapper.update(updated);
        syncRoles(updated.id(), current.roleIds(), updated.roleIds());
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }

    private Optional<UserAccount> withRoles(UserAccount account) {
        return Optional.ofNullable(account).map(found ->
                attachLinks(List.of(found), false, mapper::selectRoleLinks, UserAccount::withRoleIds).getFirst());
    }

    private void syncRoles(Long id, Set<Long> current, Set<Long> updated) {
        syncLinks(id, current, updated, mapper::deleteRoleLinks, mapper::insertRoleLinks);
    }
}
