package com.loggingx.auth.model;

/**
 * The verified contents of an access token.
 *
 * @param tokenVersion the account's token version when the token was issued; the token is revoked once it differs
 */
public record AccessTokenClaims(long userId, int tokenVersion) {
}
