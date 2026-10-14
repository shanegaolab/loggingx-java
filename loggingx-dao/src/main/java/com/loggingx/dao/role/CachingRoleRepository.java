package com.loggingx.dao.role;

import com.loggingx.dao.support.CachingRepository;
import com.loggingx.dao.support.TableCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * The {@link RoleRepository} the application uses: roles are read on every authorization check, so they are
 * served from Redis.
 */
@Primary
@Repository
public class CachingRoleRepository extends CachingRepository<Role> implements RoleRepository {

    public CachingRoleRepository(MybatisRoleRepository delegate, StringRedisTemplate redis,
                                 @Value("${loggingx.cache.ttl:10m}") Duration ttl) {
        super(delegate, new TableCache<>(redis, "roles", Role.class, ttl));
    }

    @Override
    public Optional<Role> findByCode(String code) {
        return findFirst(role -> role.code().equals(code));
    }

    @Override
    protected Long idOf(Role role) {
        return role.id();
    }
}
