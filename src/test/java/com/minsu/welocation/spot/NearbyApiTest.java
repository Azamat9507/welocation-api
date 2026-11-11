package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class NearbyApiTest {

    // Haeundae Beach, Busan
    static final double LAT = 35.1587;
    static final double LNG = 129.1604;

    @Autowired
    MockMvc mvc;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        spotRepository.deleteAll();
    }

    private Spot save(String name, SpotCategory category, Region region, double lat, double lng) {
        return spotRepository.save(new Spot(name, category, region, "test address", lat, lng, SpotSource.MANUAL, null));
    }

    private void saveBusanAndSeoul() {
        save("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, 35.1532, 129.1186);
        save("Haedong Yonggungsa", SpotCategory.ATTRACTION, Region.BUSAN, 35.1883, 129.2233);
        save("Gamcheon Culture Village", SpotCategory.ATTRACTION, Region.BUSAN, 35.0975, 129.0106);
        save("Gyeongbokgung", SpotCategory.ATTRACTION, Region.SEOUL, 37.5796, 126.9770);
    }

    // independent of MySQL: plain haversine on the same sphere radius
    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * 6_370_986 * Math.asin(Math.sqrt(a));
    }

    @Test
    void returnsSpotsInsideRadiusNearestFirst() throws Exception {
        saveBusanAndSeoul();

        mvc.perform(get("/api/spots/nearby")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG)).param("radiusKm", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Gwangalli Beach"))
                .andExpect(jsonPath("$[1].name").value("Haedong Yonggungsa"))
                .andExpect(jsonPath("$[0].region").value("BUSAN"));
    }

    @Test
    void distanceMatchesHaversine() throws Exception {
        save("Gwangalli Beach", SpotCategory.BEACH, Region.BUSAN, 35.1532, 129.1186);
        double expected = haversineMeters(LAT, LNG, 35.1532, 129.1186);

        mvc.perform(get("/api/spots/nearby")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG)).param("radiusKm", "10"))
                .andExpect(jsonPath("$[0].distanceMeters").value(closeTo(expected, expected * 0.01), Double.class));
    }

    @Test
    void filtersByCategoryAndLimitsSize() throws Exception {
        saveBusanAndSeoul();

        mvc.perform(get("/api/spots/nearby").param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .param("radiusKm", "50").param("category", "ATTRACTION"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Haedong Yonggungsa"))
                .andExpect(jsonPath("$[1].name").value("Gamcheon Culture Village"));

        mvc.perform(get("/api/spots/nearby").param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .param("radiusKm", "50").param("size", "1"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Gwangalli Beach"));
    }

    // the bounding box must never cut off a spot that is inside the circle
    @Test
    void keepsSpotsJustInsideTheRadiusInEveryDirection() throws Exception {
        double km = 9.9;
        double dLat = km / (Math.PI * 6370.986 / 180);
        double dLng = dLat / Math.cos(Math.toRadians(LAT));
        save("North", SpotCategory.VIEWPOINT, Region.BUSAN, LAT + dLat, LNG);
        save("South", SpotCategory.VIEWPOINT, Region.BUSAN, LAT - dLat, LNG);
        save("East", SpotCategory.VIEWPOINT, Region.BUSAN, LAT, LNG + dLng);
        save("West", SpotCategory.VIEWPOINT, Region.BUSAN, LAT, LNG - dLng);

        mvc.perform(get("/api/spots/nearby")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG)).param("radiusKm", "10"))
                .andExpect(jsonPath("$.length()").value(4));

        mvc.perform(get("/api/spots/nearby")
                        .param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG)).param("radiusKm", "9.8"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // same idea on random bearings, where the box corners and the curved longitude edges matter
    @Test
    void keepsSpotsJustInsideTheRadiusOnRandomBearings() throws Exception {
        Random random = new Random(7);
        double radiusKm = 50;
        double angular = radiusKm * 0.99 / 6370.986;
        int count = 40;
        for (int i = 0; i < count; i++) {
            double bearing = random.nextDouble() * 2 * Math.PI;
            double lat1 = Math.toRadians(LAT);
            double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                    + Math.cos(lat1) * Math.sin(angular) * Math.cos(bearing));
            double lng2 = Math.toRadians(LNG) + Math.atan2(Math.sin(bearing) * Math.sin(angular) * Math.cos(lat1),
                    Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
            save("Bearing " + i, SpotCategory.VIEWPOINT, Region.BUSAN, Math.toDegrees(lat2), Math.toDegrees(lng2));
        }

        mvc.perform(get("/api/spots/nearby").param("lat", String.valueOf(LAT)).param("lng", String.valueOf(LNG))
                        .param("radiusKm", String.valueOf(radiusKm)).param("size", "50"))
                .andExpect(jsonPath("$.length()").value(count));
    }

    @Test
    void rejectsOutOfRangeInput() throws Exception {
        List<Map<String, String>> bad = List.of(
                Map.of("lat", "91", "lng", "129", "radiusKm", "5"),
                Map.of("lat", "NaN", "lng", "129", "radiusKm", "5"),
                Map.of("lat", "35", "lng", "-181", "radiusKm", "5"),
                Map.of("lat", "35", "lng", "129", "radiusKm", "0"),
                Map.of("lat", "35", "lng", "129", "radiusKm", "51"),
                Map.of("lat", "35", "lng", "129", "radiusKm", "5", "size", "51"),
                Map.of("lng", "129", "radiusKm", "5"));

        for (Map<String, String> params : bad) {
            var request = get("/api/spots/nearby");
            params.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest());
        }
    }

    @Test
    void nearbyIsNotTreatedAsSpotId() throws Exception {
        mvc.perform(get("/api/spots/nearby"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("lat")));
    }

    @Test
    void queryUsesSpatialIndex() {
        saveBusanAndSeoul();
        var params = new MapSqlParameterSource()
                .addValue("lat", LAT).addValue("lng", LNG).addValue("radiusMeters", 10_000)
                .addValue("box", SpotService.boundingBox(LAT, LNG, 10))
                .addValue("category", null).addValue("limit", 20);

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + SpotRepository.NEARBY_SQL, params);

        assertThat(plan).singleElement()
                .satisfies(row -> assertThat(row.get("key")).isEqualTo("idx_spot_location"));
    }
}
