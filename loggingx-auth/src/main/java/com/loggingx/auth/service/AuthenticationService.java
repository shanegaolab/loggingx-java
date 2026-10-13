package com.loggingx.auth.service;

import com.loggingx.auth.model.AuthToken;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.TooManyRequestsException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.common.util.PasswordPolicy;
import com.loggingx.common.util.UsernamePolicy;
import com.loggingx.common.util.Usernames;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthenticationService {

    /** Role granted to self-registered users. */
    public static final String DEFAULT_ROLE_CODE = "R_USER";

    private static final String TOKEN_TYPE = "Bearer";
    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final String dummyEncodedPassword;

    public AuthenticationService(
            UserAccountRepository userAccountRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            LoginAttemptLimiter loginAttemptLimiter
    ) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.dummyEncodedPassword = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public AuthToken register(String username, String rawPassword) {
        String normalizedUsername = UsernamePolicy.requireValid(username);
        PasswordPolicy.requireValid(rawPassword);
        Set<Long> roleIds = roleRepository.findByCode(DEFAULT_ROLE_CODE).map(Role::id).map(Set::of).orElse(Set.of());
        Instant now = Instant.now();

        UserAccount account = userAccountRepository.insert(new UserAccount(
                null,
                normalizedUsername,
                passwordEncoder.encode(rawPassword),
                0,
                normalizedUsername,
                null,
                null,
                null,
                null,
                UserAccount.STATUS_ONLINE,
                roleIds,
                now,
                normalizedUsername,
                now,
                normalizedUsername
        )).orElseThrow(() -> new ConflictException("Username is already registered"));
        return issueToken(account);
    }

    public AuthToken login(String username, String rawPassword) {
        String normalizedUsername = Usernames.normalize(username);
        if (loginAttemptLimiter.isLocked(normalizedUsername)) {
            throw new TooManyRequestsException("Too many failed login attempts; try again later");
        }
        Optional<UserAccount> account = userAccountRepository.findByUsername(normalizedUsername);
        // Always run a hash comparison so response time does not reveal whether the username exists.
        String encodedPassword = account.map(UserAccount::encodedPassword).orElse(dummyEncodedPassword);
        if (!passwordEncoder.matches(rawPassword, encodedPassword) || account.isEmpty()) {
            loginAttemptLimiter.recordFailure(normalizedUsername);
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }
        loginAttemptLimiter.recordSuccess(normalizedUsername);
        if (account.get().isDeactivated()) {
            throw new UnauthorizedException("Account has been deactivated");
        }
        return issueToken(account.get());
    }

    private AuthToken issueToken(UserAccount account) {
        return new AuthToken(
                jwtTokenService.issue(account.id(), account.tokenVersion()),
                TOKEN_TYPE,
                jwtTokenService.expiresInSeconds()
        );
    }
}
