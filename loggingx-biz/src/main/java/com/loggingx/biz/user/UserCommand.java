package com.loggingx.biz.user;

import java.util.List;

/**
 * @param password  required when creating; on update a blank value keeps the current password
 * @param roleCodes codes of the roles to grant, e.g. {@code R_ADMIN}
 * @param deptId    the user's department, or {@code null} for none
 * @param postIds   the posts (job positions) the user holds; {@code null} for none
 */
public record UserCommand(
        String username,
        String password,
        String nickName,
        String gender,
        String phone,
        String email,
        String status,
        List<String> roleCodes,
        Long deptId,
        List<Long> postIds
) {
}
