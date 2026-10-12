package com.loggingx.web.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "userName must not be blank") String userName,
        @NotBlank(message = "password must not be blank") String password
) {
}
