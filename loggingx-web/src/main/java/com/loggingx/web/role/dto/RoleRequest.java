package com.loggingx.web.role.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.loggingx.biz.role.RoleCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleRequest(
        @NotBlank(message = "roleName must not be blank")
        @Size(min = 2, max = 20, message = "roleName must be between 2 and 20 characters")
        String roleName,
        @NotBlank(message = "roleCode must not be blank")
        @Size(min = 2, max = 50, message = "roleCode must be between 2 and 50 characters")
        String roleCode,
        @Size(max = 200, message = "description must be at most 200 characters")
        String description,
        boolean enabled
) {

    public RoleCommand toCommand() {
        return new RoleCommand(roleCode, roleName, description, enabled);
    }
}
