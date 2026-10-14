package com.loggingx;

import com.github.fppt.jedismock.RedisServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.io.IOException;

/**
 * Points every test application context at its own in-process Redis server instead of the configured one. Picked
 * up by component scanning, and Redis auto-configuration backs off for the connection factory defined here.
 */
@Configuration(proxyBeanMethods = false)
class TestRedisConfiguration {

    @Bean(destroyMethod = "stop")
    RedisServer redisServer() throws IOException {
        return RedisServer.newRedisServer().start();
    }

    @Bean
    LettuceConnectionFactory redisConnectionFactory(RedisServer redisServer) {
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getBindPort()));
    }
}
