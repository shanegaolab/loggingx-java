package com.loggingx.dao.account;

import com.loggingx.dao.support.EntityRepository;

import java.util.Optional;

/**
 * Usernames are unique; {@link #insert} and {@link #update} refuse a username that is already taken.
 */
public interface UserAccountRepository extends EntityRepository<UserAccount> {

    Optional<UserAccount> findByUsername(String username);
}
