package com.loggingx.biz.user;

import java.time.Instant;
import java.util.List;

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
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
}
