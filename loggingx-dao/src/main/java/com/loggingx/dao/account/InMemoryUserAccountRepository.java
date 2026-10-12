package com.loggingx.dao.account;

import com.loggingx.dao.support.AbstractInMemoryRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class InMemoryUserAccountRepository extends AbstractInMemoryRepository<UserAccount>
        implements UserAccountRepository {

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return findFirst(account -> account.username().equals(username));
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
    protected String uniqueKeyOf(UserAccount account) {
        return account.username();
    }
}
