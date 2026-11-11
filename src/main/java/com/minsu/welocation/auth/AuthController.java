package com.minsu.welocation.auth;

import com.minsu.welocation.auth.AuthDtos.LoginRequest;
import com.minsu.welocation.auth.AuthDtos.SignupRequest;
import com.minsu.welocation.auth.AuthDtos.TokenResponse;
import com.minsu.welocation.auth.AuthDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Create an account")
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        return authService.signUp(request, http.getRemoteAddr());
    }

    @Operation(summary = "Log in and get a 30-minute access token")
    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    @Operation(summary = "The logged-in user", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(Long.parseLong(jwt.getSubject()));
    }
}
