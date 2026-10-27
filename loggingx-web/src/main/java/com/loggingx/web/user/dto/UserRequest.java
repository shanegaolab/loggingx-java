package com.loggingx.web.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.loggingx.biz.user.UserCommand;
import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.UsernamePolicy;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Create or update a user. {@code password} is required on create; leave it blank on update to keep it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRequest(
        @NotBlank(message = "userName must not be blank")
        @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH, message = UsernamePolicy.LENGTH_MESSAGE)
        String userName,
        // Blank is allowed on update, so the minimum length is enforced by UserService via PasswordPolicy.
        @Size(max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
        String password,
        @Size(max = 50, message = "nickName must be at most 50 characters")
        String nickName,
        @Pattern(regexp = "[12]", message = "userGender must be 1 (male) or 2 (female)")
        String userGender,
        @Pattern(regexp = "1[3-9]\\d{9}", message = "userPhone is not a valid mobile number")
        String userPhone,
        @Email(message = "userEmail is not a valid email address")
        String userEmail,
        @Pattern(regexp = "[1-4]", message = "status must be between 1 and 4")
        String status,
        @NotEmpty(message = "userRoles must not be empty")
        List<@NotBlank String> userRoles,
        // Optional: null leaves the user without a department.
        Long deptId,
        // Optional: null or empty leaves the user without posts.
        List<@NotNull Long> postIds
) {

    public UserCommand toCommand() {
        return new UserCommand(userName, password, nickName, userGender, userPhone, userEmail, status, userRoles,
                deptId, postIds);
    }
}
