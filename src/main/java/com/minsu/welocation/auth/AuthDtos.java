package com.minsu.welocation.auth;

import com.minsu.welocation.user.Role;
import com.minsu.welocation.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    // no role field on purpose: whatever a client sends as "role" is simply not read
    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 30) String nickname) {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }

    // admin only decides which screens the web shows; every admin action is checked again by the API
    public record UserResponse(Long id, String email, String nickname, boolean admin) {

        static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole() == Role.ADMIN);
        }
    }
}
