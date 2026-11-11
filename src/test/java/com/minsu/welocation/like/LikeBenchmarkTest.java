package com.minsu.welocation.like;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.CurrentUser;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

// BENCHMARK=true ./gradlew test --tests '*LikeBenchmarkTest'
// Report: build/reports/like-benchmark.md
@SpringBootTest
@Import(TestcontainersConfig.class)
@EnabledIfEnvironmentVariable(named = "BENCHMARK", matches = "true")
class LikeBenchmarkTest {

    static final int THREADS = 1_000;
    static final int RUNS = 5;

    @Autowired
    LikeService likeService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    long spotId;
    List<Long> userIds;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO spots (name, category, region, address, latitude, longitude, source, created_at, updated_at) "
                + "VALUES ('Gwangalli Beach', 'BEACH', 'BUSAN', 'benchmark', 35.1532, 129.1186, 'MANUAL', NOW(6), NOW(6))");
        spotId = jdbc.queryForObject("SELECT MAX(id) FROM spots", Long.class);
        List<Object[]> users = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            users.add(new Object[]{"bench" + i + "@example.com", "bench" + i});
        }
        jdbc.batchUpdate("INSERT INTO users (email, password_hash, nickname, role, created_at) "
                + "VALUES (?, '$2a$10$unused', ?, 'USER', NOW(6))", users);
        userIds = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("DELETE FROM spots");
        jdbc.update("DELETE FROM users");
    }

    record Run(int count, int failed, long millis) {
    }

    // 1,000 different users like one spot, all released at once; failures are counted, not retried
    private Run run(IntConsumer like, java.util.function.IntSupplier count) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger failed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int n = i;
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    gate.await();
                    like.accept(n);
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }));
        }
        ready.await();
        long start = System.nanoTime();
        gate.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Run(count.getAsInt(), failed.get(), millis);
    }

    private void resetDb() {
        jdbc.update("DELETE FROM spot_likes");
        jdbc.update("UPDATE spots SET like_count = 0 WHERE id = ?", spotId);
    }

    private int dbCount() {
        return jdbc.queryForObject("SELECT like_count FROM spots WHERE id = ?", Integer.class, spotId);
    }

    private void insertLike(int n) {
        jdbc.update("INSERT INTO spot_likes (user_id, spot_id, created_at) VALUES (?, ?, NOW(6))", userIds.get(n), spotId);
    }

    @Test
    void noLockAgainstAtomicUpdateAgainstRowLockAgainstRedis() throws Exception {
        StringBuilder report = new StringBuilder()
                .append("# Like counter: ").append(THREADS).append(" users like one spot at the same moment\n\n")
                .append(THREADS).append(" platform threads wait on a latch and are released together; each is a different ")
                .append("user, so the right answer is always ").append(THREADS).append(". MySQL 8.4 and Redis 7.4 in ")
                .append("Testcontainers on a laptop, Hikari pool of 10, no retries. ").append(RUNS)
                .append(" runs each; each \"worst\" column is the worst of the runs on its own (they can come from ")
                .append("different runs), time is the median.\n\n")
                .append("| strategy | final count (worst run) | failed requests (worst run) | lost likes (worst run) | time (median) |\n")
                .append("|---|---|---|---|---|\n");

        // read the count, add one in Java, write it back: two requests can both read 41 and both write 42.
        // The INSERT goes last so the foreign key lock of the next row can't turn this into deadlocks.
        report.append(row("no lock: SELECT like_count, then UPDATE like_count = read + 1", () -> {
            resetDb();
            return run(n -> transactionTemplate.executeWithoutResult(status -> {
                int current = dbCount();
                jdbc.update("UPDATE spots SET like_count = ? WHERE id = ?", current + 1, spotId);
                insertLike(n);
            }), this::dbCount);
        }));

        // the arithmetic happens in MySQL, so no update is lost, but the INSERT's foreign key check takes a
        // shared lock on the spot row first; two of those then both want the exclusive lock for the UPDATE
        report.append(row("atomic UPDATE like_count = like_count + 1, after the INSERT", () -> {
            resetDb();
            return run(n -> transactionTemplate.executeWithoutResult(status -> {
                insertLike(n);
                jdbc.update("UPDATE spots SET like_count = like_count + 1 WHERE id = ?", spotId);
            }), this::dbCount);
        }));

        // correct for different users; the same user liking twice would hit the unique key and get an error
        report.append(row("atomic UPDATE first, then the INSERT", () -> {
            resetDb();
            return run(n -> transactionTemplate.executeWithoutResult(status -> {
                jdbc.update("UPDATE spots SET like_count = like_count + 1 WHERE id = ?", spotId);
                insertLike(n);
            }), this::dbCount);
        }));

        // the same checks as LikeService, but the UPDATE takes the row lock; an existing like undoes the +1
        report.append(row("atomic UPDATE first, then user and like checks, INSERT (rollback if already liked)", () -> {
            resetDb();
            return run(n -> transactionTemplate.executeWithoutResult(status -> {
                jdbc.update("UPDATE spots SET like_count = like_count + 1 WHERE id = ?", spotId);
                jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userIds.get(n));
                Integer liked = jdbc.queryForObject("SELECT COUNT(*) FROM spot_likes WHERE user_id = ? AND spot_id = ?",
                        Integer.class, userIds.get(n), spotId);
                if (liked > 0) {
                    status.setRollbackOnly();
                } else {
                    insertLike(n);
                }
            }), this::dbCount);
        }));

        // what the API runs: also correct for the same user twice and for likes mixed with unlikes
        report.append(row("SELECT ... FOR UPDATE, check user and like, INSERT, atomic UPDATE (LikeService)", () -> {
            resetDb();
            return run(n -> likeService.like(spotId, new CurrentUser(userIds.get(n))), this::dbCount);
        }));

        try (GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)) {
            redis.start();
            RedisClient client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                RedisCommands<String, String> commands = connection.sync();
                String key = "spot:" + spotId + ":likes";
                // a set of user ids: adding the same user twice is a no-op, SCARD is the count
                report.append(row("Redis SADD to a set per spot, count = SCARD (no MySQL)", () -> {
                    commands.del(key);
                    return run(n -> commands.sadd(key, String.valueOf(userIds.get(n))), () -> commands.scard(key).intValue());
                }));
            } finally {
                client.shutdown();
            }
        }

        Path out = Path.of("build/reports/like-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    interface Measured {
        Run get() throws Exception;
    }

    private static String row(String strategy, Measured measured) throws Exception {
        measured.get(); // warm-up
        Run[] runs = new Run[RUNS];
        for (int i = 0; i < RUNS; i++) {
            runs[i] = measured.get();
        }
        int worstCount = Arrays.stream(runs).mapToInt(Run::count).min().orElseThrow();
        int worstFailed = Arrays.stream(runs).mapToInt(Run::failed).max().orElseThrow();
        // a like is lost when its request succeeded but the count doesn't include it
        int worstLost = Arrays.stream(runs).mapToInt(r -> THREADS - r.failed() - r.count()).max().orElseThrow();
        long[] millis = Arrays.stream(runs).mapToLong(Run::millis).sorted().toArray();
        return "| " + strategy + " | " + worstCount + " | " + worstFailed + " | " + worstLost
                + " | " + millis[RUNS / 2] + " ms |\n";
    }
}
