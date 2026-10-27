package com.loggingx.web.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recognizes a batch that was already stored. Agents retry a request whose answer they did not get (a timeout, a
 * dropped connection) with the identical body, so a batch is identified by the SHA-256 of its token and body.
 * <p>
 * A batch is remembered only after it was stored, so a request that failed is never skipped on retry. Batches are
 * remembered in Redis for {@link #WINDOW}, shared by all instances; when Redis is unavailable, in this instance's
 * memory (the last {@link #LOCAL_CAPACITY} batches).
 */
@Component
public class IngestDeduplicator {

    static final Duration WINDOW = Duration.ofMinutes(30);
    private static final int LOCAL_CAPACITY = 20_000;
    /** After Redis fails, it is not tried again for this long, so an outage does not slow every request. */
    private static final long REDIS_BACKOFF_MILLIS = 30_000;
    private static final Logger log = LoggerFactory.getLogger(IngestDeduplicator.class);

    private final StringRedisTemplate redis;
    private volatile long redisRetryAt;
    private final Map<String, Long> local = new LinkedHashMap<>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > LOCAL_CAPACITY;
        }
    };

    public IngestDeduplicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String token, String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((token == null ? "" : token.trim()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            return "loggingx:ingest:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return whether the batch was stored within the window
     */
    public boolean seen(String key) {
        if (redisUsable()) {
            try {
                if (Boolean.TRUE.equals(redis.hasKey(key))) {
                    return true;
                }
            } catch (DataAccessException e) {
                redisFailed(e);
            }
        }
        synchronized (local) {
            Long at = local.get(key);
            return at != null && System.currentTimeMillis() - at < WINDOW.toMillis();
        }
    }

    /**
     * Remembers a stored batch.
     */
    public void remember(String key) {
        synchronized (local) {
            local.put(key, System.currentTimeMillis());
        }
        if (redisUsable()) {
            try {
                redis.opsForValue().set(key, "1", WINDOW);
            } catch (DataAccessException e) {
                redisFailed(e);
            }
        }
    }

    private boolean redisUsable() {
        return System.currentTimeMillis() >= redisRetryAt;
    }

    private void redisFailed(DataAccessException e) {
        redisRetryAt = System.currentTimeMillis() + REDIS_BACKOFF_MILLIS;
        log.warn("Redis is unavailable for ingest deduplication; using local memory for {} s: {}",
                REDIS_BACKOFF_MILLIS / 1000, e.getMessage());
    }
}
