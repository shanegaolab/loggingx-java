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
        return withLinks(mapper.selectByUsername(username));
    }

    @Override
    public boolean existsInDept(Long deptId) {
        return mapper.existsInDept(deptId);
    }

    @Override
    public boolean existsWithPost(Long postId) {
        return mapper.existsWithPost(postId);
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
        return attachAllLinks(mapper.selectAll(), true);
    }

    @Override
    protected Optional<UserAccount> load(Long id, boolean forUpdate) {
        return withLinks(mapper.selectById(id, forUpdate));
    }

    @Override
    protected Long insertRow(UserAccount account) {
        GeneratedKey key = new GeneratedKey();
        mapper.insert(account, key);
        syncLinks(key.getId(), Set.of(), account.roleIds(), mapper::deleteRoleLinks, mapper::insertRoleLinks);
        syncLinks(key.getId(), Set.of(), deptIds(account), mapper::deleteDeptLinks, mapper::insertDeptLinks);
        syncLinks(key.getId(), Set.of(), account.postIds(), mapper::deletePostLinks, mapper::insertPostLinks);
        return key.getId();
    }

    @Override
    protected void updateRow(UserAccount current, UserAccount updated) {
        mapper.update(updated);
        Long id = updated.id();
        syncLinks(id, current.roleIds(), updated.roleIds(), mapper::deleteRoleLinks, mapper::insertRoleLinks);
        syncLinks(id, deptIds(current), deptIds(updated), mapper::deleteDeptLinks, mapper::insertDeptLinks);
        syncLinks(id, current.postIds(), updated.postIds(), mapper::deletePostLinks, mapper::insertPostLinks);
    }

    @Override
    protected void deleteRow(Long id) {
        mapper.deleteById(id);
    }

    private Optional<UserAccount> withLinks(UserAccount account) {
        return Optional.ofNullable(account).map(found -> attachAllLinks(List.of(found), false).getFirst());
    }

    private List<UserAccount> attachAllLinks(List<UserAccount> accounts, boolean all) {
        List<UserAccount> withRoles = attachLinks(accounts, all, mapper::selectRoleLinks, UserAccount::withRoleIds);
        List<UserAccount> withDept = attachLinks(withRoles, all, mapper::selectDeptLinks,
                (account, deptIds) -> account.withDeptId(deptIds.stream().findFirst().orElse(null)));
        return attachLinks(withDept, all, mapper::selectPostLinks, UserAccount::withPostIds);
    }

    /**
     * The department as a link set, so it is written like the other associations.
     */
    private static Set<Long> deptIds(UserAccount account) {
        return account.deptId() == null ? Set.of() : Set.of(account.deptId());
    }
}
