package com.loggingx.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Answers requests rejected by the security filter chain with the same {@code { code, msg, data }} envelope as
 * the rest of the API, instead of an empty body.
 * <p>
 * The body is written by hand because the messages are fixed constants, which keeps this module free of a JSON
 * library dependency.
 */
public class ApiErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String UNAUTHORIZED_MESSAGE = "Authentication is required";
    private static final String FORBIDDEN_MESSAGE = "You do not have permission to perform this operation";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_MESSAGE);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        write(response, HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
    }

    private static void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + status.value() + ",\"msg\":\"" + message + "\",\"data\":null}");
    }
}
