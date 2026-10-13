package com.loggingx.common.util;

import com.loggingx.common.exception.BadRequestException;

/**
 * The single source of username rules, shared by request validation and the services that set usernames.
 * Lengths are checked on the trimmed value.
 */
public final class UsernamePolicy {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 50;
    public static final String LENGTH_MESSAGE =
            "userName must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters";
    public static final String CHARACTERS_MESSAGE = "userName must not contain whitespace or control characters";

    private UsernamePolicy() {
    }

    /**
     * @return the normalized username, see {@link Usernames#normalize}
     * @throws BadRequestException when the username is missing or does not satisfy the policy
     */
    public static String requireValid(String username) {
        String normalized = Usernames.normalize(username);
        if (normalized == null || normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new BadRequestException(LENGTH_MESSAGE);
        }
        if (normalized.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new BadRequestException(CHARACTERS_MESSAGE);
        }
        return normalized;
    }
}
