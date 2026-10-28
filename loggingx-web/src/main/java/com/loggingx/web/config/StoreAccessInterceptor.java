package com.loggingx.web.config;

import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.common.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Refuses a signed-in user's request that names a log store they may not access, as a {@code storeId} query
 * parameter or path variable, so every store-scoped endpoint is covered without checking it in each controller.
 * Lists and request bodies are filtered and checked by the controllers themselves. Requests without a signed-in user
 * (agents syncing, signed download links) authenticate otherwise and are left alone.
 */
public class StoreAccessInterceptor implements HandlerInterceptor {

    static final String STORE_ID = "storeId";

    private final StoreAccessService storeAccessService;

    public StoreAccessInterceptor(StoreAccessService storeAccessService) {
        this.storeAccessService = storeAccessService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return true;
        }
        String value = request.getParameter(STORE_ID);
        if (value == null && request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE)
                instanceof Map<?, ?> variables) {
            Object variable = variables.get(STORE_ID);
            value = variable == null ? null : variable.toString();
        }
        if (value == null || value.isBlank()) {
            return true;
        }
        long storeId;
        try {
            storeId = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("storeId must be a number");
        }
        storeAccessService.require(authentication.getName(), storeId);
        return true;
    }
}
