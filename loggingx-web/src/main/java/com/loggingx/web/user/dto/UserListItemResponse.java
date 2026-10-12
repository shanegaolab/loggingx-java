package com.loggingx.web.user.dto;

import com.loggingx.biz.user.UserView;
import com.loggingx.common.util.DateTimes;

import java.util.List;

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
        String createBy,
        String createTime,
        String updateBy,
        String updateTime
) {

    public static UserListItemResponse from(UserView user) {
        return new UserListItemResponse(user.id(), user.avatar(), user.status(), user.username(), user.gender(),
                user.nickName(), user.phone(), user.email(), user.roleCodes(), user.createdBy(),
                DateTimes.format(user.createdAt()), user.updatedBy(), DateTimes.format(user.updatedAt()));
    }
}
