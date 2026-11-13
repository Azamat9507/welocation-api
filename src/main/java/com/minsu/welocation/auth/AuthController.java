package com.minsu.welocation.auth;

import com.minsu.welocation.auth.AuthDtos.AuthorizeRequest;
import com.minsu.welocation.auth.AuthDtos.AuthorizeResponse;
import com.minsu.welocation.auth.AuthDtos.OAuthSignInRequest;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.user.IdentityProvider;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.List;

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
    private final OAuthService oauthService;

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

    @Operation(summary = "Sign-in providers that are set up: google, kakao")
    @GetMapping("/auth/oauth/providers")
    public List<String> providers() {
        return oauthService.providers().stream().map(IdentityProvider::path).toList();
    }

    @Operation(summary = "The provider's consent page URL for one sign-in attempt")
    @GetMapping("/auth/oauth/{provider}/authorize")
    public AuthorizeResponse authorize(@PathVariable String provider, @Valid @ParameterObject @ModelAttribute AuthorizeRequest request) {
        return new AuthorizeResponse(oauthService.authorizeUrl(provider(provider), request.state(), request.codeChallenge()));
    }

    @Operation(summary = "Trade the code the provider sent back for a 30-minute access token; signs up on first use")
    @PostMapping("/auth/oauth/{provider}")
    public TokenResponse oauth(@PathVariable String provider, @Valid @RequestBody OAuthSignInRequest request, HttpServletRequest http) {
        return oauthService.signIn(provider(provider), request.code(), request.codeVerifier(), http.getRemoteAddr());
    }

    private static IdentityProvider provider(String path) {
        return IdentityProvider.fromPath(path).orElseThrow(() -> new NotFoundException("unknown sign-in provider"));
    }

    @Operation(summary = "The logged-in user", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(Long.parseLong(jwt.getSubject()));
    }
}
