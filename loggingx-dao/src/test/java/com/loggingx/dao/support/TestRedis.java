package com.loggingx.dao.support;

import com.github.fppt.jedismock.RedisServer;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * An in-process Redis server with a client connected to it.
 */
public final class TestRedis implements AutoCloseable {

    private final RedisServer server;
    private final LettuceConnectionFactory connectionFactory;
    public final StringRedisTemplate template;

    public TestRedis() {
        try {
            server = RedisServer.newRedisServer().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(server.getHost(), server.getBindPort()));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new StringRedisTemplate(connectionFactory);
    }

    public void flushAll() {
        template.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @Override
    public void close() throws IOException {
        connectionFactory.destroy();
        server.stop();
    }
}
