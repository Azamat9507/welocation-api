package com.minsu.welocation.ranking;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.like.LikeService;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import com.minsu.welocation.user.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// a short backoff so the Redis outage test can wait it out
@SpringBootTest(properties = "welocation.ranking.redis-backoff=3s")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class WeeklyRankingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    LikeService likeService;

    @Autowired
    WeeklyRankingService rankingService;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("redis")
    GenericContainer<?> redisContainer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    Clock clock;

    @Autowired
    TokenService tokenService;

    @Autowired
    UserRepository userRepository;

    List<Long> userIds;

    @BeforeEach
    void setUp() {
        cleanUp();
        users(20);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM spot_tags");
        jdbc.update("DELETE FROM spots");
        jdbc.update("DELETE FROM users");
        redis.delete(redis.keys(WeeklyRankingService.KEY_PREFIX + "*"));
    }

    private void users(int count) {
        List<Object[]> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            users.add(new Object[]{"ranker" + i + "@example.com", "ranker" + i});
        }
        jdbc.batchUpdate("INSERT INTO users (email, password_hash, nickname, role, created_at) "
                + "VALUES (?, '$2a$10$unused', ?, 'USER', NOW(6))", users);
        userIds = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
    }

    private long spot(String name) {
        return spotRepository.save(new Spot(name, SpotCategory.CAFE, Region.SEOUL, "test address",
                37.5, 127.0, SpotSource.MANUAL, null)).getId();
    }

    private void like(long spotId, int fromUser, int toUser) {
        for (int i = fromUser; i < toUser; i++) {
            likeService.like(spotId, new CurrentUser(userIds.get(i)));
        }
    }

    private Double redisScore(long spotId) {
        return redis.opsForZSet().score(WeeklyRankingService.KEY_PREFIX + Week.of(clock.instant()).label(),
                String.valueOf(spotId));
    }

    private String rankingJson() throws Exception {
        return mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void ordersSpotsByThisWeeksLikesAndStopsAtTen() throws Exception {
        List<Long> spots = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = spot("Spot " + i);
            like(id, 0, i + 1);
            spots.add(id);
        }

        // no token: the ranking is public
        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.week").value(Week.of(clock.instant()).label()))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[*].rank").value(contains(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
                .andExpect(jsonPath("$.items[*].weeklyLikes").value(contains(12, 11, 10, 9, 8, 7, 6, 5, 4, 3)))
                .andExpect(jsonPath("$.items[0].spot.id").value(spots.get(11)))
                .andExpect(jsonPath("$.items[0].spot.name").value("Spot 11"))
                .andExpect(jsonPath("$.items[0].spot.likeCount").value(12))
                .andExpect(jsonPath("$.items[9].spot.id").value(spots.get(2)));
    }

    @Test
    void emptyWeekGivesAnEmptyList() throws Exception {
        spot("Nobody likes me yet");

        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void unlikeTakesTheLikeOffButLastWeeksLikesNeverCounted() throws Exception {
        long busy = spot("Busy");
        long quiet = spot("Quiet");
        like(busy, 0, 3);
        like(quiet, 0, 2);
        // user 0's like on Quiet becomes last week's, and the ranking is rebuilt from MySQL
        jdbc.update("UPDATE spot_likes SET created_at = created_at - INTERVAL 8 DAY WHERE spot_id = ? AND user_id = ?",
                quiet, userIds.get(0));
        rankingService.rebuild();
        assertThat(redisScore(quiet)).isEqualTo(1.0);

        // taking back last week's like must not take a like off this week
        likeService.unlike(quiet, new CurrentUser(userIds.get(0)));
        likeService.unlike(busy, new CurrentUser(userIds.get(1)));
        // unliking twice changes nothing
        likeService.unlike(busy, new CurrentUser(userIds.get(1)));

        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(jsonPath("$.items[*].spot.id").value(contains((int) busy, (int) quiet)))
                .andExpect(jsonPath("$.items[*].weeklyLikes").value(contains(2, 1)));
    }

    @Test
    void spotsWhoseLikesWereAllTakenBackDropOut() throws Exception {
        long spot = spot("Gone again");
        like(spot, 0, 1);
        // the first read fills the set from MySQL; from then on likes and unlikes go straight to Redis
        rankingJson();
        likeService.unlike(spot, new CurrentUser(userIds.get(0)));

        assertThat(redisScore(spot)).isEqualTo(0.0);
        mvc.perform(get("/api/spots/ranking/weekly")).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void rebuildClearsScoresThatMysqlNoLongerHas() throws Exception {
        long spot = spot("Stale");
        like(spot, 0, 1);
        rankingJson();
        assertThat(redisScore(spot)).isEqualTo(1.0);
        // MySQL now has no like this week; Redis still has the old score
        jdbc.update("UPDATE spot_likes SET created_at = created_at - INTERVAL 8 DAY");

        rankingService.rebuild();

        assertThat(redisScore(spot)).isNull();
        mvc.perform(get("/api/spots/ranking/weekly")).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void likeRolledBackWithItsTransactionIsNotCounted() throws Exception {
        long spot = spot("Rolled back");
        long other = spot("Committed");
        like(other, 0, 1);
        // the set exists, so a committed like would go straight into it
        rankingJson();

        transactionTemplate.executeWithoutResult(tx -> {
            likeService.like(spot, new CurrentUser(userIds.get(0)));
            tx.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_likes WHERE spot_id = ?", Integer.class, spot)).isZero();
        assertThat(redisScore(spot)).isNull();
    }

    @Test
    void scoreMatchesTheRowsUnderAThousandConcurrentLikesAndUnlikes() throws Exception {
        int threads = 1_000;
        cleanUp();
        users(threads);
        long spot = spot("Crowded");
        like(spot, 0, threads / 2);
        rankingService.rebuild();

        // the first half takes their like back while the second half likes, all released at once
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            CurrentUser user = new CurrentUser(userIds.get(i));
            boolean unlike = i < threads / 2;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    if (unlike) {
                        likeService.unlike(spot, user);
                    } else {
                        likeService.like(spot, user);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            worker.start();
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(errors).isEmpty();
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM spot_likes WHERE spot_id = ?", Integer.class, spot);
        assertThat(rows).isEqualTo(threads / 2);
        assertThat(redisScore(spot)).isEqualTo((double) rows);
    }

    // Ids 9, 10 and 100 with one like each: Redis orders equal scores by member text ("9" > "100" > "10"),
    // and the MySQL fallback has to come out the same.
    @Test
    void whenRedisHangsTheRankingComesFromMysqlInTheSameOrderAndTheNextReadCatchesUp() throws Exception {
        long nine = spotWithId(9);
        long ten = spotWithId(10);
        long hundred = spotWithId(100);
        like(nine, 0, 1);
        like(ten, 0, 1);
        like(hundred, 0, 1);
        long top = spot("Top");
        like(top, 0, 2);
        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(jsonPath("$.items[*].spot.id").value(contains((int) top, (int) nine, (int) hundred, (int) ten)));
        String fromRedis = rankingJson();

        String token = "Bearer " + tokenService.issue(userRepository.findById(userIds.get(5)).orElseThrow()).accessToken();

        String fromMysql;
        long likeMillis;
        String afterLike;
        // paused, not stopped: commands hang until the client timeout, the slow failure that is hardest on callers
        redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            // this read waits out the timeout once; after it Redis is skipped for the backoff
            fromMysql = rankingJson();
            long started = System.nanoTime();
            mvc.perform(post("/api/spots/{id}/like", ten).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.likeCount").value(2));
            likeMillis = (System.nanoTime() - started) / 1_000_000;
            afterLike = rankingJson();
        } finally {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        assertThat(fromMysql).isEqualTo(fromRedis);
        // well under the 500 ms Redis timeout: the like didn't wait for Redis at all
        assertThat(likeMillis).isLessThan(300);
        assertThat(afterLike).isNotEqualTo(fromRedis);
        assertThat(redisScore(ten)).isEqualTo(1.0);

        Thread.sleep(3_100);
        // the first read after the backoff sees an update was missed and refills from MySQL
        assertThat(rankingJson()).isEqualTo(afterLike);
        assertThat(redisScore(ten)).isEqualTo(2.0);
    }

    @Test
    void lostKeyIsRefilledFromMysqlOnTheNextRead() throws Exception {
        long busy = spot("Busy");
        long quiet = spot("Quiet");
        like(busy, 0, 2);
        like(quiet, 0, 1);
        String before = rankingJson();

        // what a Redis restart without persistence looks like
        redis.delete(WeeklyRankingService.KEY_PREFIX + Week.of(clock.instant()).label());

        assertThat(rankingJson()).isEqualTo(before);
        assertThat(redisScore(busy)).isEqualTo(2.0);
    }

    @Test
    void likeArrivingAfterTheKeyIsLostDoesNotStartAPartialSet() throws Exception {
        long busy = spot("Busy");
        long quiet = spot("Quiet");
        like(busy, 0, 3);
        like(quiet, 0, 1);
        redis.delete(WeeklyRankingService.KEY_PREFIX + Week.of(clock.instant()).label());

        like(quiet, 1, 2);

        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(jsonPath("$.items[*].spot.id").value(contains((int) busy, (int) quiet)))
                .andExpect(jsonPath("$.items[*].weeklyLikes").value(contains(3, 2)));
    }

    @Test
    void rebuildKeepsSpotsBelowTheTopTen() throws Exception {
        List<Long> spots = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = spot("Spot " + i);
            like(id, 0, i + 1);
            spots.add(id);
        }
        rankingService.rebuild();

        // the last spot climbs from 12th to 1st; with only the top ten rebuilt it would restart from zero
        like(spots.get(0), 1, 14);

        mvc.perform(get("/api/spots/ranking/weekly"))
                .andExpect(jsonPath("$.items[0].spot.id").value(spots.get(0)))
                .andExpect(jsonPath("$.items[0].weeklyLikes").value(14));
    }

    // created_at is stored as UTC wall-clock time; a 9-hour mix-up with KST would move every row across this line
    @Test
    void weekRunsFromItsFirstMicrosecondUpToButNotIncludingTheNextMonday() {
        Week week = Week.of(clock.instant());
        long firstMoment = spot("First moment");
        long justBefore = spot("Just before");
        long lastMoment = spot("Last moment");
        long nextWeek = spot("Next week");
        like(firstMoment, 0, 1);
        like(justBefore, 0, 1);
        like(lastMoment, 0, 1);
        like(nextWeek, 0, 1);
        likedAt(firstMoment, week.start());
        likedAt(justBefore, week.start().minusNanos(1_000));
        likedAt(lastMoment, week.end().minusNanos(1_000));
        likedAt(nextWeek, week.end());

        rankingService.rebuild();

        assertThat(redisScore(firstMoment)).isEqualTo(1.0);
        assertThat(redisScore(justBefore)).isNull();
        assertThat(redisScore(lastMoment)).isEqualTo(1.0);
        assertThat(redisScore(nextWeek)).isNull();
    }

    private void likedAt(long spotId, Instant at) {
        jdbc.update("UPDATE spot_likes SET created_at = ? WHERE spot_id = ?", LocalDateTime.ofInstant(at, ZoneOffset.UTC), spotId);
    }

    private long spotWithId(long id) {
        long saved = spot("Spot #" + id);
        jdbc.update("UPDATE spots SET id = ? WHERE id = ?", id, saved);
        return id;
    }

    @Test
    void rankingPageLoadsTheSpotsInBulk() throws Exception {
        for (int i = 0; i < 10; i++) {
            long spot = spot("Spot " + i);
            jdbc.update("INSERT INTO spot_tags (spot_id, tag) VALUES (?, 'ROOFTOP')", spot);
            like(spot, 0, 1);
        }
        // the first read after the likes fills the set from MySQL; the page itself is measured on the next one
        rankingJson();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mvc.perform(get("/api/spots/ranking/weekly")).andExpect(jsonPath("$.items.length()").value(10));
            // spots + tags for all of them + ratings for all of them; the order itself comes from Redis
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
