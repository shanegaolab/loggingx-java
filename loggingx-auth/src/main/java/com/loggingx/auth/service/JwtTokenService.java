package com.loggingx.auth.service;

import com.loggingx.auth.config.JwtProperties;
import com.loggingx.auth.model.AccessTokenClaims;
import io.jsonwebtoken.Claims;
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
import java.util.Optional;

@Service
public class JwtTokenService {

    private static final int MIN_SECRET_BYTES = 32;
    private static final String TOKEN_VERSION_CLAIM = "ver";

    private final Duration expiration;
    private final SecretKey signingKey;
    private final JwtParser parser;

    public JwtTokenService(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("loggingx.security.jwt.secret must contain at least 32 UTF-8 bytes; "
                    + "set LOGGINGX_SECURITY_JWT_SECRET or run with the dev profile");
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

    /**
     * The subject is the user id rather than the username, because usernames can change and be reused.
     */
    public String issue(long userId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim(TOKEN_VERSION_CLAIM, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * @return the claims of a valid, unexpired token, or empty for anything else
     */
    public Optional<AccessTokenClaims> parse(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            Integer tokenVersion = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
            if (claims.getSubject() == null || tokenVersion == null) {
                return Optional.empty();
            }
            return Optional.of(new AccessTokenClaims(Long.parseLong(claims.getSubject()), tokenVersion));
        } catch (JwtException | IllegalArgumentException exception) {
            // NumberFormatException is an IllegalArgumentException, so a non-numeric subject lands here too.
            return Optional.empty();
        }
    }

    public long expiresInSeconds() {
        return expiration.toSeconds();
    }
}
