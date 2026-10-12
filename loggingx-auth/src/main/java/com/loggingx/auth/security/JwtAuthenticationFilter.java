package com.loggingx.auth.security;

import com.loggingx.auth.service.JwtTokenService;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;

    public JwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository
    ) {
        this.jwtTokenService = jwtTokenService;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = resolveBearerToken(request);
            if (token != null) {
                String username = jwtTokenService.parseUsername(token);
                if (username != null && !username.isBlank()) {
                    userAccountRepository.findByUsername(username)
                            .filter(account -> !UserAccount.STATUS_DEACTIVATED.equals(account.status()))
                            .map(this::authenticationFor)
                            .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(UserAccount account) {
        List<SimpleGrantedAuthority> authorities = account.roleIds().stream()
                .map(roleRepository::findById)
                .flatMap(Optional::stream)
                .filter(Role::enabled)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.code()))
                .toList();
        return new UsernamePasswordAuthenticationToken(account.username(), null, authorities);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
