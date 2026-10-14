package com.loggingx.dao.menu;

import com.loggingx.dao.support.CachingRepository;
import com.loggingx.dao.support.TableCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * The {@link MenuRepository} the application uses: menus are read on every authorization check and route
 * request, so they are served from Redis.
 */
@Primary
@Repository
public class CachingMenuRepository extends CachingRepository<Menu> implements MenuRepository {

    public CachingMenuRepository(MybatisMenuRepository delegate, StringRedisTemplate redis,
                                 @Value("${loggingx.cache.ttl:10m}") Duration ttl) {
        super(delegate, new TableCache<>(redis, "menus", Menu.class, ttl));
    }

    @Override
    protected Long idOf(Menu menu) {
        return menu.id();
    }
}
