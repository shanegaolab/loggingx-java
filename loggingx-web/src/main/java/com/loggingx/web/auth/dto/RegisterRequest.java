package com.loggingx.web.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "userName must not be blank")
        @Size(min = 3, max = 50, message = "userName must be between 3 and 50 characters")
        String userName,
        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 100, message = "password must be between 8 and 100 characters")
        String password
) {
}
