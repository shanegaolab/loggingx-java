package com.loggingx.web.user.dto;

import com.loggingx.biz.user.UserInfo;

import java.util.List;

public record UserInfoResponse(
        Long userId,
        String userName,
        String nickName,
        String email,
        String avatar,
        List<String> roles,
        List<String> buttons
) {

    public static UserInfoResponse from(UserInfo info) {
        return new UserInfoResponse(info.userId(), info.username(), info.nickName(), info.email(), info.avatar(),
                info.roles(), info.buttons());
    }
}
