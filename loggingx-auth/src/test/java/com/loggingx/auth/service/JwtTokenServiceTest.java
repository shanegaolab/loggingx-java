package com.loggingx.auth.service;

import com.loggingx.auth.config.JwtProperties;
import com.loggingx.auth.model.AccessTokenClaims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

    private static JwtTokenService newService(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return new JwtTokenService(properties);
    }

    @Test
    void parseReturnsUserIdAndTokenVersionOfIssuedToken() {
        JwtTokenService service = newService(SECRET);

        Optional<AccessTokenClaims> claims = service.parse(service.issue(42L, 3));

        assertEquals(Optional.of(new AccessTokenClaims(42L, 3)), claims);
    }

    @Test
    void parseRejectsTokenSignedWithAnotherKey() {
        String token = newService("another-secret-that-is-at-least-32-bytes").issue(42L, 0);

        assertTrue(newService(SECRET).parse(token).isEmpty());
    }

    @Test
    void parseRejectsLegacyTokenWithUsernameSubject() {
        String legacyToken = Jwts.builder()
                .subject("alice")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertTrue(newService(SECRET).parse(legacyToken).isEmpty());
    }

    @Test
    void constructorRejectsMissingSecret() {
        assertThrows(IllegalStateException.class, () -> newService(null));
        assertThrows(IllegalStateException.class, () -> newService(""));
    }
}
