package com.minsu.welocation.like;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.bookmark.BookmarkService;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

// 1,000 threads released at the same moment against one spot. The count must match the rows exactly:
// nothing lost, nothing counted twice, no request failing on a deadlock.
@SpringBootTest
@Import(TestcontainersConfig.class)
class LikeConcurrencyTest {

    static final int THREADS = 1_000;

    @Autowired
    LikeService likeService;

    @Autowired
    BookmarkService bookmarkService;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    long spotId;
    List<Long> userIds;

    @BeforeEach
    void setUp() {
        spotId = spotRepository.save(new Spot("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, "test address",
                35.1532, 129.1186, SpotSource.MANUAL, null)).getId();
        List<Object[]> users = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            users.add(new Object[]{"liker" + i + "@example.com", "liker" + i});
        }
        jdbc.batchUpdate("INSERT INTO users (email, password_hash, nickname, role, created_at) "
                + "VALUES (?, '$2a$10$unused', ?, 'USER', NOW(6))", users);
        userIds = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM bookmarks");
        jdbc.update("DELETE FROM spots");
        jdbc.update("DELETE FROM users");
    }

    private int likeCount() {
        return jdbc.queryForObject("SELECT like_count FROM spots WHERE id = ?", Integer.class, spotId);
    }

    private int likeRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM spot_likes WHERE spot_id = ?", Integer.class, spotId);
    }

    // starts every thread, opens the gate, waits for all of them and fails on the first error any thread hit
    private static void runAtOnce(IntConsumer task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int n = i;
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    gate.await();
                    task.accept(n);
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        ready.await();
        gate.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(errors).as("errors thrown by the threads").isEmpty();
    }

    @Test
    void thousandUsersLikingAtOnceCountsEveryLike() throws InterruptedException {
        runAtOnce(n -> likeService.like(spotId, new CurrentUser(userIds.get(n))));

        assertThat(likeRows()).isEqualTo(THREADS);
        assertThat(likeCount()).isEqualTo(THREADS);
    }

    @Test
    void oneUserLikingThousandTimesAtOnceCountsOnce() throws InterruptedException {
        CurrentUser me = new CurrentUser(userIds.getFirst());

        runAtOnce(n -> likeService.like(spotId, me));

        assertThat(likeRows()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    void halfLikingWhileHalfUnlikeStaysInStep() throws InterruptedException {
        // the even users already like the spot and take it back; the odd users like it for the first time
        for (int i = 0; i < THREADS; i += 2) {
            likeService.like(spotId, new CurrentUser(userIds.get(i)));
        }

        runAtOnce(n -> {
            CurrentUser user = new CurrentUser(userIds.get(n));
            if (n % 2 == 0) {
                likeService.unlike(spotId, user);
            } else {
                likeService.like(spotId, user);
            }
        });

        assertThat(likeRows()).isEqualTo(THREADS / 2);
        assertThat(likeCount()).isEqualTo(THREADS / 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_likes WHERE MOD(user_id - ?, 2) = 0",
                Integer.class, userIds.getFirst())).isZero();
    }

    // a double click on the bookmark button, many times over: one row, and no request fails on the unique key
    @Test
    void oneUserBookmarkingThousandTimesAtOnceSavesOnce() throws InterruptedException {
        CurrentUser me = new CurrentUser(userIds.getFirst());

        runAtOnce(n -> bookmarkService.add(spotId, me));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookmarks WHERE spot_id = ?", Integer.class, spotId)).isEqualTo(1);
    }

    // Hibernate's UPDATE writes every mapped column. If like_count were one of them, saving a spot that
    // was loaded before these likes would write the old count back.
    @Test
    void savingASpotLoadedBeforeTheLikesKeepsTheCount() {
        transactionTemplate.executeWithoutResult(status -> {
            Spot stale = spotRepository.findById(spotId).orElseThrow();
            for (int i = 0; i < 3; i++) {
                likeService.like(spotId, new CurrentUser(userIds.get(i)));
            }
            stale.describe("edited after the likes");
        });

        assertThat(likeCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT description FROM spots WHERE id = ?", String.class, spotId))
                .isEqualTo("edited after the likes");
    }
}
