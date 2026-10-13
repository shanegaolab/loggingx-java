package com.loggingx.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code secret} deliberately has no default: outside the {@code dev} profile it must be configured, and startup
 * fails otherwise.
 */
@ConfigurationProperties(prefix = "loggingx.security.jwt")
public class JwtProperties {

    private String secret;
    private Duration expiration = Duration.ofHours(2);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getExpiration() {
        return expiration;
    }

    public void setExpiration(Duration expiration) {
        this.expiration = expiration;
    }
}
