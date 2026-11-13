package com.minsu.welocation.auth;

import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.ServiceUnavailableException;
import com.minsu.welocation.user.IdentityProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

// Talks to Google and Kakao for sign-in: builds the consent page URL, then trades the code the browser brings
// back for the person's id, email and name. Client secrets come from the environment and never leave the API.
@Slf4j
@Component
class OAuthClient {

    // what the API knows about the person after the code exchange
    record Profile(String subject, String email, boolean emailVerified, String name) {
    }

    private final RestClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final String redirectBase;
    private final String googleId;
    private final String googleSecret;
    private final String googleAuthUrl;
    private final String googleTokenUrl;
    private final String kakaoId;
    private final String kakaoSecret;
    private final String kakaoAuthUrl;
    private final String kakaoTokenUrl;
    private final String kakaoUserUrl;

    OAuthClient(JsonMapper json, Clock clock,
                @Value("${welocation.oauth.redirect-base}") String redirectBase,
                @Value("${welocation.oauth.google.client-id}") String googleId,
                @Value("${welocation.oauth.google.client-secret}") String googleSecret,
                @Value("${welocation.oauth.google.auth-url}") String googleAuthUrl,
                @Value("${welocation.oauth.google.token-url}") String googleTokenUrl,
                @Value("${welocation.oauth.kakao.client-id}") String kakaoId,
                @Value("${welocation.oauth.kakao.client-secret}") String kakaoSecret,
                @Value("${welocation.oauth.kakao.auth-url}") String kakaoAuthUrl,
                @Value("${welocation.oauth.kakao.token-url}") String kakaoTokenUrl,
                @Value("${welocation.oauth.kakao.user-url}") String kakaoUserUrl) {
        this.json = json;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder().requestFactory(factory).build();
        this.redirectBase = redirectBase.replaceAll("/+$", "");
        this.googleId = googleId;
        this.googleSecret = googleSecret;
        this.googleAuthUrl = googleAuthUrl;
        this.googleTokenUrl = googleTokenUrl;
        this.kakaoId = kakaoId;
        this.kakaoSecret = kakaoSecret;
        this.kakaoAuthUrl = kakaoAuthUrl;
        this.kakaoTokenUrl = kakaoTokenUrl;
        this.kakaoUserUrl = kakaoUserUrl;
    }

    // a provider shows up on the login page only once its client id and secret are set
    Set<IdentityProvider> enabled() {
        Set<IdentityProvider> on = EnumSet.noneOf(IdentityProvider.class);
        if (!googleId.isBlank() && !googleSecret.isBlank()) {
            on.add(IdentityProvider.GOOGLE);
        }
        if (!kakaoId.isBlank() && !kakaoSecret.isBlank()) {
            on.add(IdentityProvider.KAKAO);
        }
        return on;
    }

    // The callback address is fixed here, from config, so nobody can ask for a code to be sent elsewhere
    String redirectUri(IdentityProvider provider) {
        return redirectBase + "/auth/" + provider.path() + "/callback";
    }

    String authorizeUrl(IdentityProvider provider, String state, String codeChallenge) {
        requireEnabled(provider);
        UriComponentsBuilder url = switch (provider) {
            // PKCE on Google; Kakao's docs don't list it, so there the state check and the client secret carry it
            case GOOGLE -> UriComponentsBuilder.fromUriString(googleAuthUrl)
                    .queryParam("client_id", googleId)
                    .queryParam("scope", "openid email profile")
                    .queryParam("prompt", "select_account")
                    .queryParam("code_challenge", codeChallenge)
                    .queryParam("code_challenge_method", "S256");
            case KAKAO -> UriComponentsBuilder.fromUriString(kakaoAuthUrl)
                    .queryParam("client_id", kakaoId);
        };
        return url.queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri(provider))
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    Profile exchange(IdentityProvider provider, String code, String codeVerifier) {
        requireEnabled(provider);
        try {
            return switch (provider) {
                case GOOGLE -> google(code, codeVerifier);
                case KAKAO -> kakao(code, codeVerifier);
            };
        } catch (HttpClientErrorException e) {
            // a used, expired or forged code: the visitor just starts again
            log.info("{} sign-in refused the code: {}", provider, e.getStatusCode().value());
            throw new BadRequestException("code: sign-in was not completed, please try again");
        } catch (RestClientException | JacksonException | IllegalArgumentException e) {
            log.warn("{} sign-in failed: {}", provider, e.getClass().getSimpleName());
            throw new ServiceUnavailableException("sign-in with " + provider.path() + " is not available right now");
        }
    }

    private Profile google(String code, String codeVerifier) {
        JsonNode token = post(googleTokenUrl, form(googleId, googleSecret, IdentityProvider.GOOGLE, code, codeVerifier));
        // The ID token comes straight from Google's token endpoint over TLS, so its signature need not be checked
        // (OpenID Connect Core 3.1.3.7); its audience, issuer and expiry still are.
        JsonNode claims = json.readTree(payload(token.path("id_token").asString("")));
        String issuer = claims.path("iss").asString("");
        if (!googleId.equals(claims.path("aud").asString(""))
                || !(issuer.equals("https://accounts.google.com") || issuer.equals("accounts.google.com"))
                || claims.path("exp").asLong(0) < clock.instant().getEpochSecond()) {
            throw new BadRequestException("code: sign-in was not completed, please try again");
        }
        // Google: email_verified proves ownership for Gmail and for Workspace accounts (hd claim); any other
        // address was only checked once, when the Google account was made, and may have changed hands since
        String email = text(claims.path("email"));
        boolean owned = email != null && (email.toLowerCase(java.util.Locale.ROOT).endsWith("@gmail.com") || claims.hasNonNull("hd"));
        return new Profile(claims.path("sub").asString(""), email,
                claims.path("email_verified").asBoolean(false) && owned, text(claims.path("name")));
    }

    private Profile kakao(String code, String codeVerifier) {
        JsonNode token = post(kakaoTokenUrl, form(kakaoId, kakaoSecret, IdentityProvider.KAKAO, code, codeVerifier));
        String access = token.path("access_token").asString("");
        byte[] raw = http.get().uri(kakaoUserUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                .retrieve().body(byte[].class);
        JsonNode me = json.readTree(raw == null ? new byte[0] : raw);
        JsonNode account = me.path("kakao_account");
        // Kakao's own flags: the address was confirmed by the person and still receives mail
        boolean verified = account.path("is_email_verified").asBoolean(false) && account.path("is_email_valid").asBoolean(false);
        String id = me.path("id").isNumber() ? String.valueOf(me.path("id").asLong()) : "";
        return new Profile(id, text(account.path("email")), verified, text(account.path("profile").path("nickname")));
    }

    private MultiValueMap<String, String> form(String id, String secret, IdentityProvider provider, String code, String verifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", id);
        form.add("client_secret", secret);
        form.add("redirect_uri", redirectUri(provider));
        form.add("code", code);
        if (provider == IdentityProvider.GOOGLE) {
            form.add("code_verifier", verifier);
        }
        return form;
    }

    private JsonNode post(String url, MultiValueMap<String, String> form) {
        byte[] raw = http.post().uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve().body(byte[].class);
        return json.readTree(raw == null ? new byte[0] : raw);
    }

    private void requireEnabled(IdentityProvider provider) {
        if (!enabled().contains(provider)) {
            throw new ServiceUnavailableException("sign-in with " + provider.path() + " is not set up");
        }
    }

    private static String payload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new BadRequestException("code: sign-in was not completed, please try again");
        }
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        String s = node.isString() ? node.asString().strip() : "";
        return s.isEmpty() ? null : s;
    }
}
