package com.loggingx.biz.user;

import java.util.List;

/**
 * The signed-in user's profile and permissions.
 *
 * @param roles   codes of the user's enabled roles
 * @param buttons button permission marks the user holds
 */
public record UserInfo(
        Long userId,
        String username,
        String nickName,
        String email,
        String avatar,
        List<String> roles,
        List<String> buttons
) {
}
