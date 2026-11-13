package com.minsu.welocation.auth;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.user.UserIdentityRepository;
import com.minsu.welocation.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OAuthApiTest {

    static final String GOOGLE_ID = "google-client.apps.googleusercontent.com";
    static final String VERIFIER = "v".repeat(43);
    static final HttpServer PROVIDERS;
    // what the fake Google puts in the next ID token, and what the fake Kakao says about the person
    static final Map<String, Object> googleClaims = new ConcurrentHashMap<>();
    static final AtomicReference<String> kakaoUser = new AtomicReference<>();
    static final AtomicReference<String> lastTokenForm = new AtomicReference<>();
    static final AtomicInteger tokenStatus = new AtomicInteger(200);
    static final AtomicInteger ip = new AtomicInteger(1);

    static {
        try {
            PROVIDERS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        PROVIDERS.createContext("/google/token", ex -> {
            lastTokenForm.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String payload = JsonMapper.builder().build().writeValueAsString(googleClaims);
            String jwt = b64("{\"alg\":\"RS256\"}") + "." + b64(payload) + ".sig";
            reply(ex, tokenStatus.get(), "{\"access_token\":\"a\",\"id_token\":\"" + jwt + "\"}");
        });
        PROVIDERS.createContext("/kakao/token", ex -> {
            lastTokenForm.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, tokenStatus.get(), "{\"access_token\":\"kakao-access\"}");
        });
        PROVIDERS.createContext("/kakao/me", ex -> {
            boolean bearer = "Bearer kakao-access".equals(ex.getRequestHeaders().getFirst("Authorization"));
            reply(ex, bearer ? 200 : 401, kakaoUser.get());
        });
        PROVIDERS.start();
    }

    static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + PROVIDERS.getAddress().getPort();
        registry.add("welocation.oauth.redirect-base", () -> "https://web.test");
        registry.add("welocation.oauth.google.client-id", () -> GOOGLE_ID);
        registry.add("welocation.oauth.google.client-secret", () -> "google-secret");
        registry.add("welocation.oauth.google.token-url", () -> base + "/google/token");
        registry.add("welocation.oauth.kakao.client-id", () -> "kakao-rest-key");
        registry.add("welocation.oauth.kakao.client-secret", () -> "kakao-secret");
        registry.add("welocation.oauth.kakao.token-url", () -> base + "/kakao/token");
        registry.add("welocation.oauth.kakao.user-url", () -> base + "/kakao/me");
    }

    @AfterAll
    static void stopServer() {
        PROVIDERS.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserIdentityRepository identityRepository;

    @Autowired
    JsonMapper json;

    @AfterEach
    void cleanUp() {
        identityRepository.deleteAll();
        userRepository.deleteAll();
        googleClaims.clear();
        tokenStatus.set(200);
    }

    // a new client address per call, so the per-IP limit on auth calls never decides a test
    private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request) {
        String addr = "10.9." + (ip.get() / 250) + "." + (ip.getAndIncrement() % 250 + 1);
        return request.with(r -> {
            r.setRemoteAddr(addr);
            return r;
        });
    }

    private void google(String sub, String email, boolean verified, String name) {
        googleClaims.clear();
        googleClaims.put("iss", "https://accounts.google.com");
        googleClaims.put("aud", GOOGLE_ID);
        googleClaims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
        googleClaims.put("sub", sub);
        if (email != null) {
            googleClaims.put("email", email);
        }
        googleClaims.put("email_verified", verified);
        googleClaims.put("name", name);
    }

    private String signIn(String provider) throws Exception {
        String body = mvc.perform(from(post("/api/auth/oauth/" + provider)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"the-code\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("accessToken").asString();
    }

    private long me(String token) throws Exception {
        String body = mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asLong();
    }

    @Test
    void listsConfiguredProvidersAndBuildsConsentUrls() throws Exception {
        mvc.perform(get("/api/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("google"))
                .andExpect(jsonPath("$[1]").value("kakao"));

        String state = "s".repeat(32);
        String challenge = "c".repeat(43);
        mvc.perform(get("/api/auth/oauth/google/authorize").param("state", state).param("codeChallenge", challenge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", containsString("client_id=" + GOOGLE_ID)))
                .andExpect(jsonPath("$.url", containsString("redirect_uri=https://web.test/auth/google/callback")))
                .andExpect(jsonPath("$.url", containsString("state=" + state)))
                .andExpect(jsonPath("$.url", containsString("code_challenge=" + challenge)))
                .andExpect(jsonPath("$.url", containsString("code_challenge_method=S256")));
        mvc.perform(get("/api/auth/oauth/kakao/authorize").param("state", state).param("codeChallenge", challenge))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url", containsString("https://kauth.kakao.com/oauth/authorize?client_id=kakao-rest-key")))
                .andExpect(jsonPath("$.url", containsString("redirect_uri=https://web.test/auth/kakao/callback")))
                .andExpect(jsonPath("$.url", not(containsString("code_challenge"))));

        // a state that could smuggle extra parameters into the URL is refused
        mvc.perform(get("/api/auth/oauth/google/authorize").param("state", "x&redirect_uri=evil" + "a".repeat(20))
                        .param("codeChallenge", challenge))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/oauth/naver/authorize").param("state", state).param("codeChallenge", challenge))
                .andExpect(status().isNotFound());
    }

    @Test
    void googleSignsUpOnceAndSignsInAfterwards() throws Exception {
        google("g-123", "Traveler@Example.com", true, "Kim Traveler");
        long first = me(signIn("google"));
        assertThat(lastTokenForm.get()).contains("code_verifier=" + VERIFIER)
                .contains("redirect_uri=https%3A%2F%2Fweb.test%2Fauth%2Fgoogle%2Fcallback");
        long second = me(signIn("google"));

        assertThat(second).isEqualTo(first);
        var user = userRepository.findById(first).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("traveler@example.com");
        assertThat(user.getNickname()).isEqualTo("Kim Traveler");
        assertThat(user.getPasswordHash()).isNull();
        assertThat(identityRepository.count()).isEqualTo(1);

        // no password, so password login can never get in
        mvc.perform(from(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"traveler@example.com\",\"password\":\"anything-at-all\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aVerifiedEmailLinksToTheExistingAccount() throws Exception {
        mvc.perform(from(post("/api/auth/signup")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"kim@example.com\",\"password\":\"password-123\",\"nickname\":\"kim\"}"))
                .andExpect(status().isCreated());
        long existing = userRepository.findByEmail("kim@example.com").orElseThrow().getId();

        google("g-kim", "kim@example.com", true, "Kim");
        assertThat(me(signIn("google"))).isEqualTo(existing);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void anUnverifiedEmailNeverTakesOverAnAccount() throws Exception {
        mvc.perform(from(post("/api/auth/signup")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"victim@example.com\",\"password\":\"password-123\",\"nickname\":\"victim\"}"))
                .andExpect(status().isCreated());
        long victim = userRepository.findByEmail("victim@example.com").orElseThrow().getId();

        kakaoUser.set("""
                {"id": 4242, "kakao_account": {"email": "victim@example.com", "is_email_verified": false,
                 "is_email_valid": true, "profile": {"nickname": "카카오"}}}""");
        long newcomer = me(signIn("kakao"));

        assertThat(newcomer).isNotEqualTo(victim);
        var user = userRepository.findById(newcomer).orElseThrow();
        assertThat(user.getEmail()).isNull();
        assertThat(user.getNickname()).isEqualTo("카카오");
        assertThat(lastTokenForm.get()).doesNotContain("code_verifier");
    }

    @Test
    void kakaoWithoutEmailStillSignsUp() throws Exception {
        kakaoUser.set("{\"id\": 777, \"kakao_account\": {\"profile\": {\"nickname\": \"바다\"}}}");
        long first = me(signIn("kakao"));
        long again = me(signIn("kakao"));
        assertThat(again).isEqualTo(first);
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + signIn("kakao")))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.nickname").value("바다"));
    }

    @Test
    void refusesTokensMeantForAnotherAppAndRefusedCodes() throws Exception {
        google("g-1", "a@example.com", true, "A");
        googleClaims.put("aud", "someone-else.apps.googleusercontent.com");
        mvc.perform(from(post("/api/auth/oauth/google")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isBadRequest());

        google("g-1", "a@example.com", true, "A");
        googleClaims.put("exp", Instant.now().minusSeconds(60).getEpochSecond());
        mvc.perform(from(post("/api/auth/oauth/google")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isBadRequest());

        google("g-1", "a@example.com", true, "A");
        tokenStatus.set(400);
        mvc.perform(from(post("/api/auth/oauth/google")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(userRepository.count()).isZero();
    }
}
