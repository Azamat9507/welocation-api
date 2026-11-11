package com.minsu.welocation.review;

import com.jayway.jsonpath.JsonPath;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ReviewApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    Spot spot;
    String author;
    String stranger;
    String admin;

    @BeforeEach
    void setUp() {
        spot = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null));
        author = tokenFor("author@example.com");
        stranger = tokenFor("stranger@example.com");
        admin = tokenFor("admin@example.com");
    }

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
        spotRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(String email) {
        User user = userRepository.save(User.signUp(email, "$2a$10$notusedinthesetests", email.substring(0, email.indexOf('@'))));
        if (email.startsWith("admin")) {
            // there is no API that grants ADMIN, on purpose; only the database can
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", user.getId());
            user = userRepository.findById(user.getId()).orElseThrow();
        }
        return "Bearer " + tokenService.issue(user).accessToken();
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String token, String body) {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long createReview(String token, int rating) throws Exception {
        String body = mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), token,
                        "{\"rating\":%d,\"content\":\"sunset was great\"}".formatted(rating)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void writingAReviewNeedsAToken() throws Exception {
        mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), null, "{\"rating\":5,\"content\":\"nice\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), author, "{\"rating\":5,\"content\":\" nice \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("nice"))
                .andExpect(jsonPath("$.author.nickname").value("author"))
                .andExpect(content().string(not(containsString("@example.com"))));
    }

    @Test
    void oneReviewPerUserPerSpot() throws Exception {
        createReview(author, 5);

        mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), author, "{\"rating\":1,\"content\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsBadReviewsAndUnknownSpots() throws Exception {
        List<String> bad = List.of(
                "{\"rating\":0,\"content\":\"x\"}",
                "{\"rating\":6,\"content\":\"x\"}",
                "{\"content\":\"x\"}",
                "{\"rating\":5,\"content\":\"  \"}",
                "{\"rating\":5,\"content\":\"" + "a".repeat(1001) + "\"}",
                "{\"rating\":3.7,\"content\":\"x\"}",
                "{\"rating\":5,\"content\":\"\\u00a0\\u00a0\"}",
                "{\"rating\":5,\"content\":\"\\u200b\"}");
        for (String body : bad) {
            mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), author, body))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(withJson(post("/api/spots/{id}/reviews", 999_999), author, "{\"rating\":5,\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/spots/{id}/reviews", 999_999)).andExpect(status().isNotFound());
        // page * size used to overflow the offset and come back as a 500
        mvc.perform(get("/api/spots/{id}/reviews", spot.getId()).param("page", "50000000")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/spots").param("page", "50000000")).andExpect(status().isBadRequest());
    }

    @Test
    void anyoneCanReadReviewsNewestFirstWithoutEmails() throws Exception {
        createReview(author, 4);
        createReview(stranger, 2);

        mvc.perform(get("/api/spots/{id}/reviews", spot.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].author.nickname").value("stranger"))
                .andExpect(content().string(not(containsString("@example.com"))));
    }

    @Test
    void onlyTheAuthorCanEdit() throws Exception {
        long id = createReview(author, 3);
        String createdUpdatedAt = JsonPath.read(mvc.perform(get("/api/spots/{id}/reviews", spot.getId()))
                .andReturn().getResponse().getContentAsString(), "$.items[0].updatedAt");
        String edit = "{\"rating\":5,\"content\":\"changed my mind\"}";

        mvc.perform(withJson(put("/api/reviews/{id}", id), null, edit)).andExpect(status().isUnauthorized());
        mvc.perform(withJson(put("/api/reviews/{id}", id), stranger, edit))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("only the author can edit this review"));
        // moderation is deleting, not rewriting text under someone else's name
        mvc.perform(withJson(put("/api/reviews/{id}", id), admin, edit)).andExpect(status().isForbidden());
        String edited = mvc.perform(withJson(put("/api/reviews/{id}", id), author, edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(5))
                .andExpect(jsonPath("$.content").value("changed my mind"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(withJson(put("/api/reviews/{id}", 999_999), author, edit)).andExpect(status().isNotFound());

        String stored = JsonPath.read(mvc.perform(get("/api/spots/{id}/reviews", spot.getId()))
                .andReturn().getResponse().getContentAsString(), "$.items[0].updatedAt");
        assertThat((String) JsonPath.read(edited, "$.updatedAt")).isEqualTo(stored).isNotEqualTo(createdUpdatedAt);
    }

    @Test
    void onlyTheAuthorOrAnAdminCanDelete() throws Exception {
        long mine = createReview(author, 3);
        long other = createReview(stranger, 4);

        mvc.perform(delete("/api/reviews/{id}", mine)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/reviews/{id}", mine).header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/reviews/{id}", mine).header(HttpHeaders.AUTHORIZATION, author))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/reviews/{id}", mine).header(HttpHeaders.AUTHORIZATION, author))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/reviews/{id}", other).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    // the admin role is read from the database, so a token issued before a demotion no longer moderates
    @Test
    void demotedAdminCannotDeleteWithAnOldToken() throws Exception {
        long id = createReview(author, 3);
        jdbc.update("UPDATE users SET role = 'USER' WHERE email = 'admin@example.com'");

        mvc.perform(delete("/api/reviews/{id}", id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isForbidden());
    }

    // the service's exists-check can't see a parallel insert; the unique key has to turn it into a 409
    @Test
    void simultaneousPostsLeaveOneReview() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            statuses.add(pool.submit(() -> {
                start.await();
                return mvc.perform(withJson(post("/api/spots/{id}/reviews", spot.getId()), author,
                        "{\"rating\":5,\"content\":\"same time\"}")).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> status : statuses) {
            codes.add(status.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(codes).containsOnly(201, 409).filteredOn(code -> code == 201).hasSize(1);
        assertThat(reviewRepository.count()).isEqualTo(1);
    }

    @Test
    void spotPagesShowTheRating() throws Exception {
        Spot quiet = spotRepository.save(new Spot("Quiet Lake", SpotCategory.LAKE, Region.GANGWON, "test address",
                37.7, 128.0, SpotSource.MANUAL, null));
        createReview(author, 5);
        createReview(stranger, 4);

        mvc.perform(get("/api/spots/{id}", spot.getId()))
                .andExpect(jsonPath("$.reviewCount").value(2))
                .andExpect(jsonPath("$.averageRating").value(4.5));
        mvc.perform(get("/api/spots"))
                .andExpect(jsonPath("$.items[?(@.name == 'Quiet Lake')].reviewCount").value(0))
                .andExpect(jsonPath("$.items[?(@.name == 'Quiet Lake')].averageRating").value(contains(nullValue())))
                .andExpect(jsonPath("$.items[?(@.name == 'Gwangalli Beach')].averageRating").value(4.5));
        mvc.perform(get("/api/spots/{id}", quiet.getId()))
                .andExpect(jsonPath("$.averageRating").value(nullValue()));
    }
}
