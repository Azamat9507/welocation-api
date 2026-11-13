package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.TokenService;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ShortsApiTest {

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

    @Autowired
    TransactionTemplate transactions;

    Spot kart;
    Spot beach;
    String admin;
    String user;

    @BeforeEach
    void setUp() {
        kart = spotRepository.save(new Spot("Kart Arena", SpotCategory.LEISURE, Region.CHUNGBUK, "test address",
                36.85, 127.59, SpotSource.MANUAL, null));
        beach = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.15, 129.11, SpotSource.MANUAL, null));
        admin = tokenFor("admin@example.com");
        user = tokenFor("user@example.com");
    }

    @AfterEach
    void cleanUp() {
        spotRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(String email) {
        User saved = userRepository.save(User.signUp(email, "$2a$10$notusedinthesetests", email.substring(0, email.indexOf('@'))));
        if (email.startsWith("admin")) {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", saved.getId());
            saved = userRepository.findById(saved.getId()).orElseThrow();
        }
        return "Bearer " + tokenService.issue(saved).accessToken();
    }

    private void attach(String token, Spot spot, String url, int expectedStatus) throws Exception {
        mvc.perform(put("/api/admin/spots/{id}/shorts", spot.getId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void adminAttachesAVideoAndTheFeedShowsNewestFirst() throws Exception {
        attach(admin, kart, "https://youtube.com/shorts/AAAAAAAAAAA?si=x", 200);
        attach(admin, beach, "https://youtu.be/BBBBBBBBBBB", 200);

        mvc.perform(get("/api/spots/shorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Gwangalli Beach"))
                .andExpect(jsonPath("$.items[0].videoId").value("BBBBBBBBBBB"))
                .andExpect(jsonPath("$.items[1].videoId").value("AAAAAAAAAAA"))
                .andExpect(jsonPath("$.items[1].category").value("LEISURE"));

        mvc.perform(get("/api/spots/{id}", kart.getId()))
                .andExpect(jsonPath("$.shortsVideoId").value("AAAAAAAAAAA"));
    }

    @Test
    void spotsWithoutAVideoStayOutOfTheFeed() throws Exception {
        mvc.perform(get("/api/spots/shorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/spots/{id}", kart.getId()))
                .andExpect(jsonPath("$.shortsVideoId").doesNotExist());
    }

    @Test
    void removingTakesItOutOfTheFeed() throws Exception {
        attach(admin, kart, "AAAAAAAAAAA", 200);
        mvc.perform(delete("/api/admin/spots/{id}/shorts", kart.getId()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/spots/shorts")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void onlyAdminsCanChangeVideos() throws Exception {
        attach(user, kart, "AAAAAAAAAAA", 403);
        mvc.perform(delete("/api/admin/spots/{id}/shorts", kart.getId()).header(HttpHeaders.AUTHORIZATION, user))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/spots/{id}/shorts", kart.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"AAAAAAAAAAA\"}"))
                .andExpect(status().isUnauthorized());
    }

    // the role is read from the database, so a token issued before a demotion stops working at once
    @Test
    void aDemotedAdminLosesTheRight() throws Exception {
        jdbc.update("UPDATE users SET role = 'USER' WHERE email = 'admin@example.com'");
        attach(admin, kart, "AAAAAAAAAAA", 403);
    }

    @Test
    void rejectsLinksThatAreNotYouTube() throws Exception {
        attach(admin, kart, "https://evil.example/shorts/AAAAAAAAAAA", 400);
        attach(admin, kart, "", 400);
    }

    @Test
    void unknownSpotIs404() throws Exception {
        mvc.perform(put("/api/admin/spots/{id}/shorts", 999_999)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"AAAAAAAAAAA\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void meTellsTheWebWhoIsAnAdmin() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, user)).andExpect(jsonPath("$.admin").value(false));
    }

    // the nightly TourAPI sync rewrites a spot's fields; an admin's video must survive it
    @Test
    void sourceUpdatesKeepTheVideo() throws Exception {
        attach(admin, kart, "AAAAAAAAAAA", 200);
        transactions.executeWithoutResult(tx -> spotRepository.findById(kart.getId()).orElseThrow()
                .updateFromSource("Kart Arena 2", SpotCategory.LEISURE, null, Region.CHUNGBUK, "new address", 36.85, 127.59, "https://img/x.jpg"));
        mvc.perform(get("/api/spots/{id}", kart.getId()))
                .andExpect(jsonPath("$.name").value("Kart Arena 2"))
                .andExpect(jsonPath("$.shortsVideoId").value("AAAAAAAAAAA"));
    }
}
