package com.loggingx.web.dept.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.loggingx.biz.dept.DeptCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param parentId the parent department, or {@code null} or {@code 0} for a top-level department
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeptRequest(
        @Min(value = 0, message = "parentId must not be negative")
        Long parentId,
        @NotBlank(message = "deptName must not be blank")
        @Size(max = 30, message = "deptName must be at most 30 characters")
        String deptName,
        @Min(value = 0, message = "sort must be between 0 and 9999")
        @Max(value = 9999, message = "sort must be between 0 and 9999")
        Integer sort,
        @Size(max = 20, message = "leader must be at most 20 characters")
        String leader,
        @Size(max = 20, message = "phone must be at most 20 characters")
        String phone,
        @Email(message = "email must be a valid email address")
        @Size(max = 50, message = "email must be at most 50 characters")
        String email,
        boolean enabled
) {

    public DeptCommand toCommand() {
        return new DeptCommand(parentId, deptName, sort == null ? 0 : sort, leader, phone, email, enabled);
    }
}
