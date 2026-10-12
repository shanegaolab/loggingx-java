package com.loggingx.dao.role;

import com.loggingx.dao.support.EntityRepository;

import java.util.Optional;

/**
 * Role codes are unique; {@link #insert} and {@link #update} refuse a code that is already taken.
 */
public interface RoleRepository extends EntityRepository<Role> {

    Optional<Role> findByCode(String code);
}
