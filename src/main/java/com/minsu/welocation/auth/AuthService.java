package com.minsu.welocation.auth;

import com.minsu.welocation.auth.AuthDtos.LoginRequest;
import com.minsu.welocation.auth.AuthDtos.SignupRequest;
import com.minsu.welocation.auth.AuthDtos.TokenResponse;
import com.minsu.welocation.auth.AuthDtos.UserResponse;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.ConflictException;
import com.minsu.welocation.common.Constraints;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    static final int LOGINS_PER_MINUTE_PER_EMAIL = 5;
    static final int AUTH_CALLS_PER_MINUTE_PER_IP = 20;
    // BCrypt only looks at the first 72 bytes; a longer password would silently match its own prefix
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String BAD_CREDENTIALS = "invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;
    // compared against when the email is unknown, so both failures take the same BCrypt time
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       TokenService tokenService, RateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public UserResponse signUp(SignupRequest request, String clientIp) {
        rateLimiter.check(RateLimiter.clientKey(clientIp), AUTH_CALLS_PER_MINUTE_PER_IP);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BadRequestException("password: must be at most 72 bytes");
        }
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("email is already registered");
        }
        try {
            User user = userRepository.saveAndFlush(
                    User.signUp(email, passwordEncoder.encode(request.password()), request.nickname().strip()));
            return UserResponse.from(user);
        } catch (DataIntegrityViolationException e) {
            // two signups with the same email at once: the unique key catches the second
            if (Constraints.isViolationOf(e, "uk_user_email")) {
                throw new ConflictException("email is already registered");
            }
            throw e;
        }
    }

    public TokenResponse login(LoginRequest request, String clientIp) {
        String email = normalize(request.email());
        rateLimiter.check(RateLimiter.clientKey(clientIp), AUTH_CALLS_PER_MINUTE_PER_IP);
        rateLimiter.check("login:" + email, LOGINS_PER_MINUTE_PER_EMAIL);

        Optional<User> user = userRepository.findByEmail(email);
        boolean matches = passwordEncoder.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw new UnauthorizedException(BAD_CREDENTIALS);
        }
        return tokenService.issue(user.get());
    }

    public UserResponse me(long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("user not found"));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
