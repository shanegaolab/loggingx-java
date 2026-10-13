package com.loggingx.auth.security;

import com.loggingx.auth.model.AccessTokenClaims;
import com.loggingx.auth.service.JwtTokenService;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication filter(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        new JwtAuthenticationFilter(jwtTokenService, userAccountRepository, roleRepository)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static UserAccount account(String username, int tokenVersion, String status) {
        return new UserAccount(7L, username, "encoded", tokenVersion, username, null, null, null, null, status,
                Set.of(), null, null, null, null);
    }

    @Test
    void authenticatesAsTheAccountsCurrentUsername() throws Exception {
        when(jwtTokenService.parse("token")).thenReturn(Optional.of(new AccessTokenClaims(7L, 1)));
        when(userAccountRepository.findById(7L))
                .thenReturn(Optional.of(account("renamed", 1, UserAccount.STATUS_ONLINE)));

        assertEquals("renamed", filter("token").getName());
    }

    @Test
    void rejectsTokenIssuedBeforeTokenVersionChanged() throws Exception {
        when(jwtTokenService.parse("token")).thenReturn(Optional.of(new AccessTokenClaims(7L, 1)));
        when(userAccountRepository.findById(7L))
                .thenReturn(Optional.of(account("alice", 2, UserAccount.STATUS_ONLINE)));

        assertNull(filter("token"));
    }

    @Test
    void rejectsDeactivatedAccount() throws Exception {
        when(jwtTokenService.parse("token")).thenReturn(Optional.of(new AccessTokenClaims(7L, 1)));
        when(userAccountRepository.findById(7L))
                .thenReturn(Optional.of(account("alice", 1, UserAccount.STATUS_DEACTIVATED)));

        assertNull(filter("token"));
    }

    @Test
    void rejectsTokenOfDeletedAccount() throws Exception {
        when(jwtTokenService.parse("token")).thenReturn(Optional.of(new AccessTokenClaims(7L, 1)));
        when(userAccountRepository.findById(7L)).thenReturn(Optional.empty());

        assertNull(filter("token"));
    }
}
