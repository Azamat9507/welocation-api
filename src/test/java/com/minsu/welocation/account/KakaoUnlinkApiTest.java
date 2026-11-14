package com.minsu.welocation.account;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import com.minsu.welocation.user.IdentityProvider;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserIdentity;
import com.minsu.welocation.user.UserIdentityRepository;
import com.minsu.welocation.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@TestPropertySource(properties = {
        "welocation.oauth.kakao.admin-key=test-admin-key",
        "welocation.oauth.kakao.app-id=1649988"
})
class KakaoUnlinkApiTest {

    static final String KAKAO_AUTH = "KakaoAK test-admin-key";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserIdentityRepository identityRepository;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    JdbcTemplate jdbc;

    Spot beach;

    @BeforeEach
    void setUp() {
        beach = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM bookmarks");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM user_identities");
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM spots");
    }

    private User kakaoUser(String kakaoId) {
        User user = userRepository.save(User.fromProvider(null, "traveler"));
        identityRepository.save(new UserIdentity(user, IdentityProvider.KAKAO, kakaoId));
        return user;
    }

    // a like (with the spot's count raised, as LikeService does), a bookmark and a review
    private void leaveTraces(User user) {
        jdbc.update("INSERT INTO spot_likes (user_id, spot_id, created_at) VALUES (?, ?, NOW(6))", user.getId(), beach.getId());
        jdbc.update("UPDATE spots SET like_count = like_count + 1 WHERE id = ?", beach.getId());
        jdbc.update("INSERT INTO bookmarks (user_id, spot_id, created_at) VALUES (?, ?, NOW(6))", user.getId(), beach.getId());
        jdbc.update("INSERT INTO reviews (spot_id, author_id, rating, content, created_at, updated_at) VALUES (?, ?, 5, 'great', NOW(6), NOW(6))",
                beach.getId(), user.getId());
    }

    private ResultActions unlink(String authorization, String appId, String kakaoId) throws Exception {
        var request = post("/api/auth/oauth/kakao/unlink")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("app_id", appId)
                .param("user_id", kakaoId)
                .param("referrer_type", "UNLINK_FROM_APPS");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mvc.perform(request);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void deletesAUserKakaoWasTheOnlyWayInForWithEverythingTheyLeft() throws Exception {
        User user = kakaoUser("4100000001");
        leaveTraces(user);
        User other = kakaoUser("4100000002");
        leaveTraces(other);

        unlink(KAKAO_AUTH, "1649988", "4100000001").andExpect(status().isOk());

        assertThat(userRepository.existsById(user.getId())).isFalse();
        assertThat(count("SELECT COUNT(*) FROM user_identities WHERE user_id = ?", user.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM spot_likes WHERE user_id = ?", user.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM bookmarks WHERE user_id = ?", user.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE author_id = ?", user.getId())).isZero();
        assertThat(count("SELECT like_count FROM spots WHERE id = ?", beach.getId())).isEqualTo(1);
        // the other user is untouched
        assertThat(userRepository.existsById(other.getId())).isTrue();
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE author_id = ?", other.getId())).isEqualTo(1);
    }

    @Test
    void aUserWithAnotherWayInOnlyLosesTheKakaoLink() throws Exception {
        User user = kakaoUser("4100000003");
        identityRepository.save(new UserIdentity(user, IdentityProvider.GOOGLE, "g-1"));
        leaveTraces(user);

        unlink(KAKAO_AUTH, "1649988", "4100000003").andExpect(status().isOk());

        assertThat(userRepository.existsById(user.getId())).isTrue();
        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.KAKAO, "4100000003")).isEmpty();
        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, "g-1")).isPresent();
        assertThat(count("SELECT COUNT(*) FROM reviews WHERE author_id = ?", user.getId())).isEqualTo(1);
    }

    @Test
    void refusesCallsWithoutTheAdminKey() throws Exception {
        User user = kakaoUser("4100000004");

        unlink(null, "1649988", "4100000004").andExpect(status().isUnauthorized());
        unlink("KakaoAK wrong-key", "1649988", "4100000004").andExpect(status().isUnauthorized());
        unlink("Bearer test-admin-key", "1649988", "4100000004").andExpect(status().isUnauthorized());

        assertThat(userRepository.existsById(user.getId())).isTrue();
    }

    @Test
    void ignoresOtherAppsAndUnknownUsers() throws Exception {
        User user = kakaoUser("4100000005");

        unlink(KAKAO_AUTH, "999", "4100000005").andExpect(status().isOk());
        unlink(KAKAO_AUTH, "1649988", "4199999999").andExpect(status().isOk());

        assertThat(userRepository.existsById(user.getId())).isTrue();
    }
}
