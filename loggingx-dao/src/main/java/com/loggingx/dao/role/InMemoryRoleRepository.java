package com.loggingx.dao.role;

import com.loggingx.dao.support.AbstractInMemoryRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class InMemoryRoleRepository extends AbstractInMemoryRepository<Role> implements RoleRepository {

    @Override
    public Optional<Role> findByCode(String code) {
        return findFirst(role -> role.code().equals(code));
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
    protected String uniqueKeyOf(Role role) {
        return role.code();
    }
}
