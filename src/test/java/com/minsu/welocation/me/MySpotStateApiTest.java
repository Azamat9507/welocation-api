package com.minsu.welocation.me;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class MySpotStateApiTest {

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

    long spot;
    String kim;
    String lee;

    @BeforeEach
    void setUp() {
        spot = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null)).getId();
        kim = tokenFor("kim@example.com");
        lee = tokenFor("lee@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM bookmarks");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM spots");
        jdbc.update("DELETE FROM users");
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.signUp(email, "$2a$10$unused", email.substring(0, email.indexOf('@'))));
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, token);
    }

    @Test
    void showsWhatIDidAndNothingSomeoneElseDid() throws Exception {
        mvc.perform(as(kim, get("/api/me/spots/{id}", spot)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.bookmarked").value(false))
                .andExpect(jsonPath("$.reviewId").value(nullValue()));

        mvc.perform(as(kim, post("/api/spots/{id}/like", spot))).andExpect(status().isOk());
        mvc.perform(as(kim, post("/api/spots/{id}/bookmark", spot))).andExpect(status().isNoContent());
        String review = mvc.perform(as(kim, post("/api/spots/{id}/reviews", spot))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\": 5, \"content\": \"좋아요\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reviewId = Long.parseLong(review.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(as(kim, get("/api/me/spots/{id}", spot)))
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.bookmarked").value(true))
                .andExpect(jsonPath("$.reviewId").value(reviewId));
        // Lee sees only Lee's own state
        mvc.perform(as(lee, get("/api/me/spots/{id}", spot)))
                .andExpect(jsonPath("$.liked").value(false))
                .andExpect(jsonPath("$.bookmarked").value(false))
                .andExpect(jsonPath("$.reviewId").value(nullValue()));
    }

    @Test
    void needsATokenAndAnExistingSpot() throws Exception {
        mvc.perform(get("/api/me/spots/{id}", spot)).andExpect(status().isUnauthorized());
        mvc.perform(as(kim, get("/api/me/spots/{id}", 99_999_999))).andExpect(status().isNotFound());
        mvc.perform(as(kim, get("/api/me/spots/abc"))).andExpect(status().isBadRequest());
    }
}
