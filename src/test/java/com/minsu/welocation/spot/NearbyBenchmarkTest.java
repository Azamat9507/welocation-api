package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// BENCHMARK=true ./gradlew test --tests '*NearbyBenchmarkTest'
// Report: build/reports/nearby-benchmark.md
@SpringBootTest
@Import(TestcontainersConfig.class)
@EnabledIfEnvironmentVariable(named = "BENCHMARK", matches = "true")
class NearbyBenchmarkTest {

    static final int SPOTS = 100_000;
    static final int RUNS = 30;
    // Seoul City Hall
    static final double CENTER_LAT = 37.5663;
    static final double CENTER_LNG = 126.9779;

    // what the query would look like without the bounding box: every row gets a distance computed
    static final String FULL_SCAN_SQL = """
            SELECT s.id, ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) AS distanceMeters
            FROM spots s
            WHERE ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) <= :radiusMeters
              AND (:category IS NULL OR s.category = :category)
            ORDER BY distanceMeters, s.id
            LIMIT :limit
            """;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    SpotService spotService;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        jdbc.getJdbcTemplate().update("DELETE FROM spots");
    }

    @Test
    void fullScanAgainstSpatialIndexAgainstWideningSearch() throws IOException {
        seed();

        StringBuilder report = new StringBuilder()
                .append("# Nearby search: full scan vs one indexed pass vs widening search\n\n")
                .append(SPOTS).append(" spots: 20% clustered around Seoul City Hall (Gaussian, σ ≈ 15 km), 80% spread ")
                .append("evenly over Korea's bounding box (33.1-38.6°N, 124.6-131.0°E, sea included); 1% are LAKE, ")
                .append("the rest CAFE. MySQL 8.4 in Testcontainers on a laptop, ").append(RUNS)
                .append(" runs each after warm-up, centre Seoul City Hall. No spot is a BEACH.\n\n")
                .append("- full scan: distance computed for every row (JDBC)\n")
                .append("- one pass: bounding box on the spatial index, whole radius at once (`SpotRepository.findNearby` ")
                .append("in a read-only transaction)\n")
                .append("- widening: 1, 3, 10, 30 km, then the full radius, stopping once `size` are found, skipping ahead ")
                .append("when a short step predicts the full radius won't fill the page (`SpotService.nearby`)\n\n")
                .append("All three return the same ids in the same order (asserted). Numbers compare the approaches; ")
                .append("they are not production latency.\n\n")
                .append("| radius | category | size | matching spots inside | full scan | one pass | widening |\n")
                .append("|---|---|---|---|---|---|---|\n");

        // BEACH has no spots at all: the case where a widening search could pay for every step
        record Case(double radiusKm, SpotCategory category, int size) {
        }
        for (Case c : List.of(new Case(1, null, 20), new Case(10, null, 20), new Case(50, null, 20),
                new Case(50, null, 50), new Case(50, SpotCategory.LAKE, 20), new Case(50, SpotCategory.LAKE, 50),
                new Case(30, SpotCategory.BEACH, 20), new Case(50, SpotCategory.BEACH, 20))) {
            String category = c.category() == null ? null : c.category().name();
            String box = SpotService.boundingBox(CENTER_LAT, CENTER_LNG, c.radiusKm());
            var params = new MapSqlParameterSource()
                    .addValue("lat", CENTER_LAT).addValue("lng", CENTER_LNG).addValue("radiusMeters", c.radiusKm() * 1000)
                    .addValue("category", category).addValue("limit", c.size());
            var search = new SpotDtos.NearbySearch(CENTER_LAT, CENTER_LNG, c.radiusKm(), c.category(), c.size());

            Runnable fullScan = () -> jdbc.queryForList(FULL_SCAN_SQL, params);
            // same read-only transaction the service runs in, so only the search strategy differs
            TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
            readOnly.setReadOnly(true);
            Runnable onePass = () -> readOnly.executeWithoutResult(status ->
                    spotRepository.findNearby(CENTER_LAT, CENTER_LNG, c.radiusKm() * 1000, box, category, c.size()));
            Runnable widening = () -> spotService.nearby(search);

            List<Long> fullIds = jdbc.queryForList(FULL_SCAN_SQL, params).stream()
                    .map(row -> ((Number) row.get("id")).longValue()).toList();
            List<Long> onePassIds = spotRepository.findNearby(CENTER_LAT, CENTER_LNG, c.radiusKm() * 1000, box, category, c.size())
                    .stream().map(SpotRepository.NearbyRow::getId).toList();
            List<Long> wideningIds = spotService.nearby(search).stream().map(SpotDtos.NearbySpot::id).toList();
            assertThat(onePassIds).isEqualTo(fullIds);
            assertThat(wideningIds).isEqualTo(fullIds);

            Long inside = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM spots s
                    WHERE ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) <= :radiusMeters
                      AND (:category IS NULL OR s.category = :category)
                    """, params, Long.class);

            report.append("| ").append((int) c.radiusKm()).append(" km | ").append(category == null ? "any" : category)
                    .append(" | ").append(c.size())
                    .append(" | ").append(inside)
                    .append(" | ").append(String.format("%.2f ms", medianMillis(fullScan)))
                    .append(" | ").append(String.format("%.2f ms", medianMillis(onePass)))
                    .append(" | ").append(String.format("%.2f ms", medianMillis(widening)))
                    .append(" |\n");
        }

        Path out = Path.of("build/reports/nearby-benchmark.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private void seed() {
        Random random = new Random(42);
        Instant now = Instant.now();
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (int i = 0; i < SPOTS; i++) {
            // a fifth of the spots crowd around Seoul like real data would; the rest cover the whole country
            boolean seoul = i < SPOTS / 5;
            double lat = seoul ? CENTER_LAT + random.nextGaussian() * 0.15 : 33.1 + random.nextDouble() * 5.5;
            double lng = seoul ? CENTER_LNG + random.nextGaussian() * 0.15 : 124.6 + random.nextDouble() * 6.4;
            batch.add(new MapSqlParameterSource()
                    .addValue("name", "spot " + i)
                    .addValue("lat", lat)
                    .addValue("lng", lng)
                    .addValue("category", i % 100 == 0 ? "LAKE" : "CAFE")
                    .addValue("now", Timestamp.from(now)));
            if (batch.size() == 5_000 || i == SPOTS - 1) {
                jdbc.batchUpdate("""
                        INSERT INTO spots (name, category, region, address, latitude, longitude, source, created_at, updated_at)
                        VALUES (:name, :category, 'SEOUL', 'benchmark', :lat, :lng, 'MANUAL', :now, :now)
                        """, batch.toArray(MapSqlParameterSource[]::new));
                batch.clear();
            }
        }
        jdbc.getJdbcTemplate().execute("ANALYZE TABLE spots");
    }

    private double medianMillis(Runnable query) {
        for (int i = 0; i < 5; i++) {
            query.run();
        }
        long[] nanos = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            query.run();
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos[RUNS / 2] / 1_000_000.0;
    }
}
