package com.loggingx.dao.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * Caches a whole table as one JSON value in Redis, for small tables that are read far more often than written.
 * A missing or unreadable value is reloaded; when Redis is unreachable, every read goes to the loader. Writers
 * {@link #evict} after committing, and the time-to-live bounds how long a value cached by a racing reader can lag.
 */
public class TableCache<T> {

    private static final Logger log = LoggerFactory.getLogger(TableCache.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;
    private final String key;
    private final JavaType listType;
    private final Duration ttl;

    /**
     * @param name distinguishes the table; bump the version in the key prefix when the cached shape changes
     */
    public TableCache(StringRedisTemplate redis, String name, Class<T> type, Duration ttl) {
        this.redis = redis;
        this.key = "loggingx:cache:v1:" + name;
        this.listType = JSON.getTypeFactory().constructCollectionType(List.class, type);
        this.ttl = ttl;
    }

    public List<T> get(Supplier<List<T>> loader) {
        String cached;
        try {
            cached = redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.warn("Cannot read {} from Redis, loading from the database: {}", key, e.toString());
            return List.copyOf(loader.get());
        }
        if (cached != null) {
            try {
                return List.copyOf(JSON.<List<T>>readValue(cached, listType));
            } catch (JacksonException e) {
                log.warn("Discarding unreadable {} in Redis: {}", key, e.toString());
            }
        }
        List<T> loaded = List.copyOf(loader.get());
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(loaded), ttl);
        } catch (DataAccessException e) {
            log.warn("Cannot cache {} in Redis: {}", key, e.toString());
        }
        return loaded;
    }

    public void evict() {
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            log.warn("Cannot evict {} from Redis; it expires within its time-to-live: {}", key, e.toString());
        }
    }
}
