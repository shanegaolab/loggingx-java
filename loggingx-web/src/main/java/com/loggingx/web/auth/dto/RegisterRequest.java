package com.loggingx.web.auth.dto;

import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.UsernamePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "userName must not be blank")
        @Size(min = UsernamePolicy.MIN_LENGTH, max = UsernamePolicy.MAX_LENGTH, message = UsernamePolicy.LENGTH_MESSAGE)
        String userName,
        @NotBlank(message = "password must not be blank")
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.LENGTH_MESSAGE)
        String password
) {
}
