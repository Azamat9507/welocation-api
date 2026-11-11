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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
            ORDER BY distanceMeters
            LIMIT :limit
            """;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.getJdbcTemplate().update("DELETE FROM spots");
    }

    @Test
    void spatialIndexAgainstFullScan() throws IOException {
        seed();

        StringBuilder report = new StringBuilder()
                .append("# Nearby search: full scan vs spatial index\n\n")
                .append(SPOTS).append(" spots: 20% clustered around Seoul City Hall (Gaussian, σ ≈ 15 km), 80% spread ")
                .append("evenly over Korea's bounding box (33.1-38.6°N, 124.6-131.0°E, sea included). MySQL 8.4 in ")
                .append("Testcontainers on a laptop, ").append(RUNS).append(" runs each after warm-up, centre Seoul City Hall, ")
                .append("LIMIT 20. EXPLAIN rows are MySQL's estimate of rows read. Both queries return the same ids ")
                .append("(asserted). Numbers are for comparing the two plans, not a production latency.\n\n")
                .append("| radius | spots inside | returned | full scan: EXPLAIN rows | full scan median | index: EXPLAIN rows | index median |\n")
                .append("|---|---|---|---|---|---|---|\n");

        for (double radiusKm : new double[]{1, 10, 50}) {
            var params = new MapSqlParameterSource()
                    .addValue("lat", CENTER_LAT).addValue("lng", CENTER_LNG).addValue("radiusMeters", radiusKm * 1000)
                    .addValue("box", SpotService.boundingBox(CENTER_LAT, CENTER_LNG, radiusKm))
                    .addValue("category", null).addValue("limit", 20);

            List<Long> fullIds = ids(FULL_SCAN_SQL, params);
            List<Long> indexIds = ids(SpotRepository.NEARBY_SQL, params);
            assertThat(indexIds).as("bounding box must not change the result").isEqualTo(fullIds);

            Long inside = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM spots s
                    WHERE ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) <= :radiusMeters
                    """, params, Long.class);

            report.append("| ").append((int) radiusKm).append(" km | ").append(inside)
                    .append(" | ").append(indexIds.size())
                    .append(" | ").append(explainRows(FULL_SCAN_SQL, params))
                    .append(" | ").append(String.format("%.2f ms", medianMillis(FULL_SCAN_SQL, params)))
                    .append(" | ").append(explainRows(SpotRepository.NEARBY_SQL, params))
                    .append(" | ").append(String.format("%.2f ms", medianMillis(SpotRepository.NEARBY_SQL, params)))
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
                    .addValue("now", Timestamp.from(now)));
            if (batch.size() == 5_000 || i == SPOTS - 1) {
                jdbc.batchUpdate("""
                        INSERT INTO spots (name, category, region, address, latitude, longitude, source, created_at, updated_at)
                        VALUES (:name, 'CAFE', 'SEOUL', 'benchmark', :lat, :lng, 'MANUAL', :now, :now)
                        """, batch.toArray(MapSqlParameterSource[]::new));
                batch.clear();
            }
        }
        jdbc.getJdbcTemplate().execute("ANALYZE TABLE spots");
    }

    private List<Long> ids(String sql, MapSqlParameterSource params) {
        return jdbc.queryForList(sql, params).stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private String explainRows(String sql, MapSqlParameterSource params) {
        Map<String, Object> plan = jdbc.queryForList("EXPLAIN " + sql, params).getFirst();
        return plan.get("rows") + " (" + plan.get("type") + (plan.get("key") == null ? "" : ", " + plan.get("key")) + ")";
    }

    private double medianMillis(String sql, MapSqlParameterSource params) {
        for (int i = 0; i < 5; i++) {
            jdbc.queryForList(sql, params);
        }
        long[] nanos = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            jdbc.queryForList(sql, params);
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        return nanos[RUNS / 2] / 1_000_000.0;
    }
}
