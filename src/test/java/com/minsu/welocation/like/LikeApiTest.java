package com.minsu.welocation.like;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// likes and bookmarks over HTTP; the counting under load is LikeConcurrencyTest
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class LikeApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    Spot beach;
    Spot cafe;
    String kim;
    String lee;

    @BeforeEach
    void setUp() {
        beach = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null));
        cafe = spotRepository.save(new Spot("Rooftop Cafe", SpotCategory.CAFE, Region.SEOUL, "test address",
                37.5, 127.0, SpotSource.MANUAL, null));
        kim = tokenFor("kim@example.com");
        lee = tokenFor("lee@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM bookmarks");
        jdbc.update("DELETE FROM spots");
        jdbc.update("DELETE FROM users");
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.signUp(email, "$2a$10$notusedinthesetests", email.substring(0, email.indexOf('@'))));
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, token);
    }

    @Test
    void likingNeedsAToken() throws Exception {
        mvc.perform(post("/api/spots/{id}/like", beach.getId())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/spots/{id}/like", beach.getId())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/spots/{id}/bookmark", beach.getId())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/spots/{id}/bookmark", beach.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/bookmarks")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonNumericSpotIdIs400() throws Exception {
        mvc.perform(as(kim, post("/api/spots/abc/like"))).andExpect(status().isBadRequest());
        mvc.perform(as(kim, post("/api/spots/abc/bookmark"))).andExpect(status().isBadRequest());
    }

    @Test
    void likingTwiceCountsOnceAndUnlikingTwiceIsHarmless() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(kim, post("/api/spots/{id}/like", beach.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liked").value(true))
                    .andExpect(jsonPath("$.likeCount").value(1));
        }
        mvc.perform(as(lee, post("/api/spots/{id}/like", beach.getId())))
                .andExpect(jsonPath("$.likeCount").value(2));

        for (int i = 0; i < 2; i++) {
            mvc.perform(as(kim, delete("/api/spots/{id}/like", beach.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liked").value(false))
                    .andExpect(jsonPath("$.likeCount").value(1));
        }
    }

    @Test
    void spotPagesShowTheLikeCount() throws Exception {
        mvc.perform(as(kim, post("/api/spots/{id}/like", beach.getId())));
        mvc.perform(as(lee, post("/api/spots/{id}/like", beach.getId())));

        mvc.perform(get("/api/spots/{id}", beach.getId())).andExpect(jsonPath("$.likeCount").value(2));
        mvc.perform(get("/api/spots?category=BEACH")).andExpect(jsonPath("$.items[0].likeCount").value(2));
        mvc.perform(get("/api/spots/{id}", cafe.getId())).andExpect(jsonPath("$.likeCount").value(0));
    }

    @Test
    void unknownSpotIs404() throws Exception {
        long missing = cafe.getId() + 1000;
        mvc.perform(as(kim, post("/api/spots/{id}/like", missing))).andExpect(status().isNotFound());
        mvc.perform(as(kim, delete("/api/spots/{id}/like", missing))).andExpect(status().isNotFound());
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", missing))).andExpect(status().isNotFound());
        mvc.perform(as(kim, delete("/api/spots/{id}/bookmark", missing))).andExpect(status().isNotFound());
    }

    @Test
    void tokenOfADeletedAccountCannotLike() throws Exception {
        jdbc.update("DELETE FROM users WHERE email = 'kim@example.com'");

        mvc.perform(as(kim, post("/api/spots/{id}/like", beach.getId()))).andExpect(status().isUnauthorized());
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", beach.getId()))).andExpect(status().isUnauthorized());
    }

    @Test
    void bookmarksAreIdempotentAndListedNewestFirst() throws Exception {
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", beach.getId()))).andExpect(status().isNoContent());
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", beach.getId()))).andExpect(status().isNoContent());
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", cafe.getId()))).andExpect(status().isNoContent());

        mvc.perform(as(kim, get("/api/me/bookmarks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[*].name").value(contains("Rooftop Cafe", "Gwangalli Beach")));

        mvc.perform(as(kim, delete("/api/spots/{id}/bookmark", cafe.getId()))).andExpect(status().isNoContent());
        mvc.perform(as(kim, delete("/api/spots/{id}/bookmark", cafe.getId()))).andExpect(status().isNoContent());
        mvc.perform(as(kim, get("/api/me/bookmarks")))
                .andExpect(jsonPath("$.items[*].name").value(contains("Gwangalli Beach")));
    }

    @Test
    void bookmarksArePrivate() throws Exception {
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", beach.getId())));

        mvc.perform(as(lee, get("/api/me/bookmarks")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // lee removing kim's bookmark only removes lee's (none)
        mvc.perform(as(lee, delete("/api/spots/{id}/bookmark", beach.getId()))).andExpect(status().isNoContent());
        mvc.perform(as(kim, get("/api/me/bookmarks"))).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void bookmarkPageSizeIsCapped() throws Exception {
        mvc.perform(as(kim, get("/api/me/bookmarks?size=51"))).andExpect(status().isBadRequest());
        mvc.perform(as(kim, get("/api/me/bookmarks?page=10001"))).andExpect(status().isBadRequest());
    }
}
