package com.loggingx.web.role.dto;

import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.role.Role;

public record RoleListItemResponse(
        Long roleId,
        String roleName,
        String roleCode,
        String description,
        boolean enabled,
        String createTime
) {

    public static RoleListItemResponse from(Role role) {
        return new RoleListItemResponse(role.id(), role.name(), role.code(), role.description(), role.enabled(),
                DateTimes.format(role.createdAt()));
    }
}
