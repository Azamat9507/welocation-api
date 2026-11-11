package com.minsu.welocation.auth;

import com.jayway.jsonpath.JsonPath;
import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.user.Role;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class AuthApiTest {

    // every test gets its own client address so the per-IP rate limit never leaks between tests
    static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    final String ip = "10.0." + (NEXT_IP.get() / 250) + "." + (NEXT_IP.getAndIncrement() % 250 + 1);

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    private RequestPostProcessor fromClient() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpServletRequestBuilder json(String url, String body) {
        return post(url).with(fromClient()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void signUp(String email, String password) throws Exception {
        mvc.perform(json("/api/auth/signup",
                        "{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"minsu\"}".formatted(email, password)))
                .andExpect(status().isCreated());
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(json("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String token(JwtEncoder encoder, String issuer, Instant issuedAt, Instant expiresAt) {
        return sign(encoder, JwtClaimsSet.builder().issuer(issuer).subject("1")
                .issuedAt(issuedAt).expiresAt(expiresAt).claim("roles", List.of("USER")).build());
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void signUpStoresAHashAndNeverReturnsIt() throws Exception {
        mvc.perform(json("/api/auth/signup", "{\"email\":\"Minsu@Example.COM\",\"password\":\"sea-view-2026\",\"nickname\":\" 민수 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("minsu@example.com"))
                .andExpect(jsonPath("$.nickname").value("민수"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User saved = userRepository.findByEmail("minsu@example.com").orElseThrow();
        assertThat(saved.getPasswordHash()).startsWith("$2").doesNotContain("sea-view-2026");
    }

    @Test
    void emailIsUniqueIgnoringCase() throws Exception {
        signUp("minsu@example.com", "sea-view-2026");

        mvc.perform(json("/api/auth/signup", "{\"email\":\"MINSU@example.com\",\"password\":\"another-pass\",\"nickname\":\"x\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsInvalidSignups() throws Exception {
        String koreanPassword = "가".repeat(25); // 25 characters, 75 bytes
        List<String> bad = List.of(
                "{\"email\":\"not-an-email\",\"password\":\"sea-view-2026\",\"nickname\":\"x\"}",
                "{\"email\":\"a@example.com\",\"password\":\"short7!\",\"nickname\":\"x\"}",
                "{\"email\":\"a@example.com\",\"password\":\"" + koreanPassword + "\",\"nickname\":\"x\"}",
                "{\"email\":\"a@example.com\",\"password\":\"sea-view-2026\",\"nickname\":\" \"}",
                "{\"email\":\"a@example.com\",\"password\":\"sea-view-2026\"}");
        for (String body : bad) {
            mvc.perform(json("/api/auth/signup", body)).andExpect(status().isBadRequest());
        }

        mvc.perform(json("/api/auth/signup", "{\"email\":\"a@example.com\",\"password\":\"" + koreanPassword + "\",\"nickname\":\"x\"}"))
                .andExpect(jsonPath("$.detail").value("password: must be at most 72 bytes"));
        mvc.perform(json("/api/auth/signup", "{\"email\": oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("malformed request body"));
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void roleSentByTheClientIsIgnored() throws Exception {
        mvc.perform(json("/api/auth/signup",
                        "{\"email\":\"a@example.com\",\"password\":\"sea-view-2026\",\"nickname\":\"x\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmail("a@example.com").orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(jwtDecoder.decode(login("a@example.com", "sea-view-2026")).getClaimAsStringList("roles"))
                .containsExactly("USER");
    }

    @Test
    void loginGivesATokenThatOpensMe() throws Exception {
        signUp("minsu@example.com", "sea-view-2026");

        mvc.perform(json("/api/auth/login", "{\"email\":\"minsu@example.com\",\"password\":\"sea-view-2026\"}"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800));

        String token = login("Minsu@Example.com", "sea-view-2026");
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("minsu@example.com"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        signUp("minsu@example.com", "sea-view-2026");

        String wrongPassword = mvc.perform(json("/api/auth/login", "{\"email\":\"minsu@example.com\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(json("/api/auth/login", "{\"email\":\"nobody@example.com\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword).contains("invalid email or password");
    }

    @Test
    void meRejectsMissingAndBadTokens() throws Exception {
        Instant now = Instant.now();
        String valid = token(jwtEncoder, "welocation", now, now.plusSeconds(600));
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        NimbusJwtEncoder otherEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
        String unsignedHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));

        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value("missing or invalid token"));
        // the decoder's reason (bad signature, timestamps) stays on the server
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(jwtEncoder, "someone-else", now, now.plusSeconds(600))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));

        List<String> bad = List.of(
                "garbage",
                valid.substring(0, valid.length() - 2) + (valid.endsWith("AA") ? "BB" : "AA"),
                token(jwtEncoder, "welocation", now.minusSeconds(3600), now.minusSeconds(1800)),
                token(otherEncoder, "welocation", now, now.plusSeconds(600)),
                token(jwtEncoder, "someone-else", now, now.plusSeconds(600)),
                sign(jwtEncoder, JwtClaimsSet.builder().issuer("welocation").subject("1").issuedAt(now).build()),
                sign(jwtEncoder, JwtClaimsSet.builder().issuer("welocation").subject("abc")
                        .issuedAt(now).expiresAt(now.plusSeconds(600)).build()),
                unsignedHeader + valid.substring(valid.indexOf('.'), valid.lastIndexOf('.') + 1));
        for (String token : bad) {
            mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void readingSpotsStaysOpenAndEverythingElseIsClosed() throws Exception {
        mvc.perform(get("/api/spots")).andExpect(status().isOk());
        mvc.perform(post("/api/spots").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/not-a-real-endpoint")).andExpect(status().isUnauthorized());
    }

    // MySQL's default collation would treat these as kim@example.com
    @Test
    void lookalikeEmailsAreDifferentAccounts() throws Exception {
        signUp("kim@example.com", "sea-view-2026");

        for (String lookalike : List.of("kïm@exämple.com", "ｋim@example.com", "kím@example.com")) {
            mvc.perform(json("/api/auth/login", "{\"email\":\"%s\",\"password\":\"sea-view-2026\"}".formatted(lookalike)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(json("/api/auth/signup", "{\"email\":\"kïm@example.com\",\"password\":\"sea-view-2026\",\"nickname\":\"x\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void staleTokenDoesNotLockOutOpenEndpoints() throws Exception {
        String expired = token(jwtEncoder, "welocation", Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
        signUp("minsu@example.com", "sea-view-2026");

        mvc.perform(get("/api/spots").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk());
        mvc.perform(json("/api/auth/login", "{\"email\":\"minsu@example.com\",\"password\":\"sea-view-2026\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk());
    }

    @Test
    void loginIsRateLimitedPerEmail() throws Exception {
        // its own email: the limiter's counters live as long as the Spring context, across tests
        signUp("limited@example.com", "sea-view-2026");

        List<String> spellings = List.of("limited@example.com", "Limited@Example.com", " LIMITED@example.com ");
        for (int i = 0; i < AuthService.LOGINS_PER_MINUTE_PER_EMAIL; i++) {
            // a new address and another spelling each time: only the per-email limit can kick in, and it
            // has to see all spellings as one account
            int n = i;
            String email = spellings.get(i % spellings.size());
            mvc.perform(post("/api/auth/login").with(request -> {
                        request.setRemoteAddr("10.200.0." + (n + 1));
                        return request;
                    }).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"wrong-pass\"}".formatted(email)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(json("/api/auth/login", "{\"email\":\"limited@example.com\",\"password\":\"sea-view-2026\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    void authCallsAreRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < AuthService.AUTH_CALLS_PER_MINUTE_PER_IP; i++) {
            mvc.perform(json("/api/auth/login", "{\"email\":\"user%d@example.com\",\"password\":\"wrong-pass\"}".formatted(i)))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(json("/api/auth/login", "{\"email\":\"fresh@example.com\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
