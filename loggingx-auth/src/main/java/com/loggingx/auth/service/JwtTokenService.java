package com.loggingx.auth.service;

import com.loggingx.auth.config.JwtProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    private static final int MIN_SECRET_BYTES = 32;

    private final Duration expiration;
    private final SecretKey signingKey;
    private final JwtParser parser;

    public JwtTokenService(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("loggingx.security.jwt.secret must contain at least 32 UTF-8 bytes");
        }
        Duration expiration = properties.getExpiration();
        if (expiration == null || !expiration.isPositive()) {
            throw new IllegalStateException("loggingx.security.jwt.expiration must be greater than zero");
        }
        this.expiration = expiration;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // JwtParser is immutable and thread-safe, so build it once instead of on every request.
        this.parser = Jwts.parser().verifyWith(signingKey).build();
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    public String parseUsername(String token) {
        try {
            return parser.parseSignedClaims(token).getPayload().getSubject();
        } catch (JwtException | IllegalArgumentException exception) {
            return null;
        }
    }

    public long expiresInSeconds() {
        return expiration.toSeconds();
    }
}
