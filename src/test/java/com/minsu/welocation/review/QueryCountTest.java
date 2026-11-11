package com.minsu.welocation.review;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.bookmark.BookmarkService;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import com.minsu.welocation.spot.SpotTag;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Pins how many SQL statements a list page costs, so an N+1 can't creep back in unnoticed.
// Without the fixes these were 23 and 23 (one extra query per row); see TASK.md T4.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class QueryCountTest {

    static final int ROWS = 20;

    @Autowired
    MockMvc mvc;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    BookmarkService bookmarkService;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    Statistics statistics;

    @BeforeEach
    void enableStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void cleanUp() {
        statistics.setStatisticsEnabled(false);
        reviewRepository.deleteAll();
        jdbc.update("DELETE FROM bookmarks");
        spotRepository.deleteAll();
        userRepository.deleteAll();
    }

    private long statementsFor(String url, Object... vars) throws Exception {
        return statementsFor(null, url, vars);
    }

    private long statementsFor(String token, String url, Object... vars) throws Exception {
        statistics.clear();
        var request = get(url, vars);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(ROWS));
        return statistics.getPrepareStatementCount();
    }

    @Test
    void reviewPageLoadsAuthorsWithTheReviews() throws Exception {
        Spot spot = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null));
        for (int i = 0; i < ROWS; i++) {
            User author = userRepository.save(User.signUp("user" + i + "@example.com", "$2a$10$unused", "user" + i));
            reviewRepository.save(new Review(spot, author, 1 + i % 5, "review " + i));
        }

        // spot exists + page of reviews joined with authors + count
        assertThat(statementsFor("/api/spots/{id}/reviews?size=" + ROWS, spot.getId())).isEqualTo(3);
    }

    @Test
    void spotPageLoadsTagsAndRatingsInBulk() throws Exception {
        User author = userRepository.save(User.signUp("writer@example.com", "$2a$10$unused", "writer"));
        for (int i = 0; i < ROWS; i++) {
            Spot spot = new Spot("Spot " + i, SpotCategory.CAFE, Region.SEOUL, "test address",
                    37.5, 127.0, SpotSource.MANUAL, null);
            spot.addTag(SpotTag.ROOFTOP);
            spot.addTag(SpotTag.NIGHT_VIEW);
            spotRepository.save(spot);
            reviewRepository.save(new Review(spot, author, 4, "good"));
        }

        // page of spots + count + tags for the whole page + ratings for the whole page
        assertThat(statementsFor("/api/spots?size=" + ROWS)).isEqualTo(4);
    }

    @Test
    void bookmarkPageLoadsSpotsWithTheBookmarks() throws Exception {
        User me = userRepository.save(User.signUp("saver@example.com", "$2a$10$unused", "saver"));
        for (int i = 0; i < ROWS; i++) {
            Spot spot = new Spot("Spot " + i, SpotCategory.CAFE, Region.SEOUL, "test address",
                    37.5, 127.0, SpotSource.MANUAL, null);
            spot.addTag(SpotTag.ROOFTOP);
            bookmarkService.add(spotRepository.save(spot).getId(), new CurrentUser(me.getId()));
        }
        String token = "Bearer " + tokenService.issue(me).accessToken();

        // page of bookmarks joined with spots + count + tags for the whole page + ratings for the whole page
        assertThat(statementsFor(token, "/api/me/bookmarks?size=" + ROWS)).isEqualTo(4);
    }
}
