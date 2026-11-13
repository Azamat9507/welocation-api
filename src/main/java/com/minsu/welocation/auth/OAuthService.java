package com.minsu.welocation.auth;

import com.minsu.welocation.auth.AuthDtos.TokenResponse;
import com.minsu.welocation.auth.OAuthClient.Profile;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.Constraints;
import com.minsu.welocation.user.IdentityProvider;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserIdentity;
import com.minsu.welocation.user.UserIdentityRepository;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final int NICKNAME_MAX = 30;

    private final OAuthClient client;
    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;
    private final TransactionTemplate tx;

    public Set<IdentityProvider> providers() {
        return client.enabled();
    }

    public String authorizeUrl(IdentityProvider provider, String state, String codeChallenge) {
        return client.authorizeUrl(provider, state, codeChallenge);
    }

    // The provider is called outside any transaction (it can take seconds); only the lookup and insert are inside.
    public TokenResponse signIn(IdentityProvider provider, String code, String codeVerifier, String clientIp) {
        rateLimiter.check(RateLimiter.clientKey(clientIp), AuthService.AUTH_CALLS_PER_MINUTE_PER_IP);
        Profile profile = client.exchange(provider, code, codeVerifier);
        if (profile.subject() == null || profile.subject().isBlank() || profile.subject().length() > 100) {
            throw new BadRequestException("code: sign-in was not completed, please try again");
        }
        User user;
        try {
            user = tx.execute(status -> findOrCreate(provider, profile));
        } catch (DataIntegrityViolationException e) {
            // the same first sign-in twice at once: the other request created the user, so look it up again
            if (!Constraints.isViolationOf(e, "uk_identity_provider_subject")) {
                throw e;
            }
            user = tx.execute(status -> findOrCreate(provider, profile));
        }
        return tokenService.issue(user);
    }

    // 1. this Google/Kakao account signed in before: the same user
    // 2. the provider vouches for an email an existing user signed up with: link to that user. An unverified
    //    email is never matched, or anyone could make a provider account with someone else's address and walk in.
    // 3. otherwise a new user, with the email only when it is verified and not taken
    private User findOrCreate(IdentityProvider provider, Profile profile) {
        var known = identityRepository.findByProviderAndSubject(provider, profile.subject());
        if (known.isPresent()) {
            return known.get().getUser();
        }
        String email = profile.emailVerified() && profile.email() != null && profile.email().length() <= 254
                ? profile.email().toLowerCase(Locale.ROOT) : null;
        User user = email == null ? null : userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = userRepository.saveAndFlush(User.fromProvider(email, nickname(profile, email)));
        }
        identityRepository.saveAndFlush(new UserIdentity(user, provider, profile.subject()));
        return user;
    }

    private static String nickname(Profile profile, String email) {
        String name = profile.name();
        if (name == null && email != null) {
            name = email.substring(0, email.indexOf('@'));
        }
        if (name == null || name.isBlank()) {
            name = "traveler";
        }
        name = name.strip();
        return name.length() > NICKNAME_MAX ? name.substring(0, NICKNAME_MAX) : name;
    }
}
