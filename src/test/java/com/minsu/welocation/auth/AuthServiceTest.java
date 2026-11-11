package com.minsu.welocation.auth;

import com.minsu.welocation.auth.AuthDtos.LoginRequest;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Timing can't be asserted reliably in an API test, so this checks the cause instead: an unknown
// email still goes through one BCrypt comparison, just like a wrong password does.
class AuthServiceTest {

    @Test
    void unknownEmailStillPaysForAPasswordCheck() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("$2a$10$dummy");
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        AuthService service = new AuthService(users, encoder, mock(TokenService.class), new RateLimiter(Clock.systemUTC()));

        assertThatThrownBy(() -> service.login(new LoginRequest("nobody@example.com", "guess-1234"), "10.9.9.9"))
                .isInstanceOf(UnauthorizedException.class);
        verify(encoder).matches("guess-1234", "$2a$10$dummy");
    }
}
