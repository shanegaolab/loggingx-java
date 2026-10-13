package com.loggingx.common.util;

import com.loggingx.common.exception.BadRequestException;

/**
 * The single source of password rules, shared by request validation and the services that set passwords.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 100;
    public static final String LENGTH_MESSAGE =
            "password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters";

    private PasswordPolicy() {
    }

    /**
     * @throws BadRequestException when the password is missing or does not satisfy the policy
     */
    public static void requireValid(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new BadRequestException(LENGTH_MESSAGE);
        }
    }
}
