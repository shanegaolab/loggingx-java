package com.loggingx.web.auth.dto;

import com.loggingx.auth.model.AuthToken;

/**
 * @param token     JWT to send back as {@code Authorization: Bearer <token>}
 * @param expiresIn token lifetime in seconds
 */
public record AuthTokenResponse(String token, String tokenType, long expiresIn) {

    public static AuthTokenResponse from(AuthToken token) {
        return new AuthTokenResponse(token.accessToken(), token.tokenType(), token.expiresInSeconds());
    }
}
