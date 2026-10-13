package com.loggingx.web.auth.dto;

import com.loggingx.common.util.PasswordPolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "userName must not be blank")
        @Size(min = 3, max = 50, message = "userName must be between 3 and 50 characters")
        String userName,
        @NotBlank(message = "password must not be blank")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
        String password
) {
}
