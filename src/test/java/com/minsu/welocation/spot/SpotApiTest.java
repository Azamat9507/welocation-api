package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SpotApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SpotRepository spotRepository;

    @AfterEach
    void cleanUp() {
        spotRepository.deleteAll();
    }

    private Spot save(String name, SpotCategory category, Region region, SpotTag... tags) {
        return save(name, "test address", category, region, tags);
    }

    private Spot save(String name, String address, SpotCategory category, Region region, SpotTag... tags) {
        Spot spot = new Spot(name, category, region, address, 35.1, 129.1, SpotSource.MANUAL, null);
        for (SpotTag tag : tags) {
            spot.addTag(tag);
        }
        return spotRepository.save(spot);
    }

    @Test
    void filtersByRegionCategoryAndTag() throws Exception {
        save("Sea Cafe", SpotCategory.CAFE, Region.BUSAN, SpotTag.OCEAN_VIEW, SpotTag.ROOFTOP);
        save("Plain Cafe", SpotCategory.CAFE, Region.BUSAN);
        save("Busan Beach", SpotCategory.BEACH, Region.BUSAN, SpotTag.OCEAN_VIEW);
        save("Seoul Cafe", SpotCategory.CAFE, Region.SEOUL, SpotTag.OCEAN_VIEW);

        mvc.perform(get("/api/spots")
                        .param("region", "BUSAN").param("category", "CAFE").param("tag", "OCEAN_VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Sea Cafe"));

        mvc.perform(get("/api/spots").param("region", "BUSAN"))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void pagesNewestFirst() throws Exception {
        for (int i = 1; i <= 5; i++) {
            save("Spot " + i, SpotCategory.LAKE, Region.GANGWON);
        }

        mvc.perform(get("/api/spots").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Spot 3"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    // half the TourAPI cafés have no photo; a first page of empty cards looked broken (V8)
    @Test
    void spotsWithAPhotoComeFirstThenNewest() throws Exception {
        Spot oldWithPhoto = save("Old with photo", SpotCategory.CAFE, Region.BUSAN);
        oldWithPhoto.addImage("https://example.com/old.jpg");
        spotRepository.save(oldWithPhoto);
        save("New without photo", SpotCategory.CAFE, Region.BUSAN);
        Spot newWithPhoto = save("New with photo", SpotCategory.CAFE, Region.BUSAN);
        newWithPhoto.addImage("https://example.com/new.jpg");
        spotRepository.save(newWithPhoto);
        save("Seoul without photo", SpotCategory.CAFE, Region.SEOUL);

        mvc.perform(get("/api/spots").param("region", "BUSAN"))
                .andExpect(jsonPath("$.items[*].name").value(contains("New with photo", "Old with photo", "New without photo")));
        mvc.perform(get("/api/spots").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Seoul without photo", "New without photo")));
    }

    @Test
    void rejectsPageSizeAboveLimit() throws Exception {
        mvc.perform(get("/api/spots").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("size")));
    }

    @Test
    void unknownEnumIsBadRequestWithoutJavaTypeNames() throws Exception {
        mvc.perform(get("/api/spots").param("region", "MOON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("region: invalid value"))
                .andExpect(content().string(not(containsString("com.minsu"))));
    }

    @Test
    void textSearchMatchesNameOrAddress() throws Exception {
        save("Haeundae Blue Cafe", "Busan Haeundae-gu 1", SpotCategory.CAFE, Region.BUSAN);
        save("Gwangan Bridge View", "Busan Suyeong-gu 2", SpotCategory.CAFE, Region.BUSAN);
        save("Haeundae Beach", "Busan Haeundae-gu 3", SpotCategory.BEACH, Region.BUSAN);
        save("Lake Cafe", "Gapyeong 4", SpotCategory.CAFE, Region.GYEONGGI);

        mvc.perform(get("/api/spots").param("q", "  bridge "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name").value(contains("Gwangan Bridge View")));
        mvc.perform(get("/api/spots").param("q", "Suyeong"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Gwangan Bridge View")));
        mvc.perform(get("/api/spots").param("q", "haeundae"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Haeundae Beach", "Haeundae Blue Cafe")));
        mvc.perform(get("/api/spots").param("q", "haeundae").param("category", "CAFE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Haeundae Blue Cafe"));
    }

    @Test
    void textSearchTreatsWildcardsLiterally() throws Exception {
        save("100% Coffee", SpotCategory.CAFE, Region.SEOUL);
        save("Plain Coffee", SpotCategory.CAFE, Region.SEOUL);
        save("Snake_Case Bar", SpotCategory.CAFE, Region.SEOUL);
        save("Back\\slash Cafe", SpotCategory.CAFE, Region.SEOUL);

        mvc.perform(get("/api/spots").param("q", "%"))
                .andExpect(jsonPath("$.items[*].name").value(contains("100% Coffee")));
        mvc.perform(get("/api/spots").param("q", "_"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Snake_Case Bar")));
        mvc.perform(get("/api/spots").param("q", "\\"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Back\\slash Cafe")));
    }

    @Test
    void blankTextSearchIsUnfiltered() throws Exception {
        save("One", SpotCategory.CAFE, Region.SEOUL);
        save("Two", SpotCategory.CAFE, Region.SEOUL);

        mvc.perform(get("/api/spots").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void rejectsTextSearchAboveFiftyChars() throws Exception {
        mvc.perform(get("/api/spots").param("q", "a".repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("q")));
        mvc.perform(get("/api/spots").param("q", "a".repeat(50)))
                .andExpect(status().isOk());
    }

    @Test
    void detailIncludesTagsAndImagesInOrder() throws Exception {
        Spot spot = new Spot("Lake View", SpotCategory.CAFE, Region.GYEONGGI, "Gapyeong", 37.8, 127.5,
                SpotSource.MANUAL, null);
        spot.describe("quiet cafe by the lake");
        spot.addTag(SpotTag.LAKE_VIEW);
        spot.addTag(SpotTag.PARKING);
        spot.addImage("https://img.example/1.jpg");
        spot.addImage("https://img.example/2.jpg");
        Long id = spotRepository.save(spot).getId();

        mvc.perform(get("/api/spots/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("quiet cafe by the lake"))
                .andExpect(jsonPath("$.tags[0]").value("LAKE_VIEW"))
                .andExpect(jsonPath("$.tags[1]").value("PARKING"))
                .andExpect(jsonPath("$.images[0]").value("https://img.example/1.jpg"))
                .andExpect(jsonPath("$.images[1]").value("https://img.example/2.jpg"));

        mvc.perform(get("/api/spots").param("region", "GYEONGGI"))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value("https://img.example/1.jpg"));
    }

    @Test
    void missingSpotIs404WithoutStackTrace() throws Exception {
        mvc.perform(get("/api/spots/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("spot 999999 not found"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void nonNumericIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/spots/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("id: invalid value"));
    }
}
