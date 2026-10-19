package com.loggingx.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Records one audit entry for every request handled by a REST controller.
 * Request bodies, query values, tokens and passwords are intentionally excluded.
 */
public final class OperationAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OperationAuditInterceptor.class);
    private static final String START_TIME_ATTRIBUTE = OperationAuditInterceptor.class.getName() + ".startNanos";
    private static final String ANONYMOUS = "anonymous";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        Object startValue = request.getAttribute(START_TIME_ATTRIBUTE);
        long durationMs = startValue instanceof Long startNanos
                ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                : -1L;
        String handlerName = handler == null ? "unknown" : handler.getClass().getSimpleName();
        String username = currentUsername();
        int status = response.getStatus();

        if (exception != null || status >= 500) {
            log.error("event=user.operation method={} path={} username={} remoteIp={} status={} durationMs={} handler={}",
                    request.getMethod(), request.getRequestURI(), username, request.getRemoteAddr(), status,
                    durationMs, handlerName, exception);
        } else if (status >= 400) {
            log.warn("event=user.operation method={} path={} username={} remoteIp={} status={} durationMs={} handler={}",
                    request.getMethod(), request.getRequestURI(), username, request.getRemoteAddr(), status,
                    durationMs, handlerName);
        } else {
            log.info("event=user.operation method={} path={} username={} remoteIp={} status={} durationMs={} handler={}",
                    request.getMethod(), request.getRequestURI(), username, request.getRemoteAddr(), status,
                    durationMs, handlerName);
        }
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ANONYMOUS;
        }
        return authentication.getName();
    }
}
