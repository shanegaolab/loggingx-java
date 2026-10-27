package com.loggingx.biz.user;

import java.time.Instant;
import java.util.List;

/**
 * @param deptName  name of the user's department, or {@code null} when the user belongs to none
 * @param postIds   ids of the user's posts, in post order
 * @param postNames names of the user's posts, in the same order as {@code postIds}
 */
public record UserView(
        Long id,
        String username,
        String nickName,
        String gender,
        String phone,
        String email,
        String avatar,
        String status,
        List<String> roleCodes,
        Long deptId,
        String deptName,
        List<Long> postIds,
        List<String> postNames,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
