package com.loggingx.biz.user;

/**
 * User list filters; text filters match case-insensitively by substring, blank values are ignored.
 *
 * @param deptId matches the users of this department and of its descendants
 */
public record UserQuery(
        Integer current,
        Integer size,
        String userName,
        String userGender,
        String userPhone,
        String userEmail,
        String status,
        Long deptId
) {
}
