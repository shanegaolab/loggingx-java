package com.loggingx.biz.user;

/**
 * User list filters; text filters match case-insensitively by substring, blank values are ignored.
 */
public record UserQuery(
        Integer current,
        Integer size,
        String userName,
        String userGender,
        String userPhone,
        String userEmail,
        String status
) {
}
