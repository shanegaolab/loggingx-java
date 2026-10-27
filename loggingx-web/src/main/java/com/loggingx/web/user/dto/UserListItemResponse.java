package com.loggingx.web.user.dto;

import com.loggingx.biz.user.UserView;
import com.loggingx.common.util.DateTimes;

import java.util.List;

/**
 * @param deptName  {@code null} when the user belongs to no department
 * @param postNames in the same order as {@code postIds}
 */
public record UserListItemResponse(
        Long id,
        String avatar,
        String status,
        String userName,
        String userGender,
        String nickName,
        String userPhone,
        String userEmail,
        List<String> userRoles,
        Long deptId,
        String deptName,
        List<Long> postIds,
        List<String> postNames,
        String createBy,
        String createTime,
        String updateBy,
        String updateTime
) {

    public static UserListItemResponse from(UserView user) {
        return new UserListItemResponse(user.id(), user.avatar(), user.status(), user.username(), user.gender(),
                user.nickName(), user.phone(), user.email(), user.roleCodes(), user.deptId(), user.deptName(),
                user.postIds(), user.postNames(), user.createdBy(), DateTimes.format(user.createdAt()),
                user.updatedBy(), DateTimes.format(user.updatedAt()));
    }
}
