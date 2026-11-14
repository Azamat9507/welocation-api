package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.spot.SceneImporter.Report;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "welocation.scenes.file=classpath:scenes-test.json")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SceneApiTest {

    static final String PHOTO = "https://tong.visitkorea.or.kr/cms/resource/24/3543324_image2_1.jpg";

    @Autowired
    MockMvc mvc;

    @Autowired
    SceneImporter importer;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TokenService tokenService;

    long nami;

    @BeforeEach
    void setUp() {
        // already synced from TourAPI before the import: the import must reuse it, not copy it
        nami = spotRepository.save(new Spot("남이섬", SpotCategory.ATTRACTION, Region.GANGWON, "강원도 춘천시",
                37.79, 127.52, SpotSource.TOURAPI, "900001")).getId();
    }

    @AfterEach
    void cleanUp() {
        spotRepository.deleteAll();
        userRepository.deleteAll();
    }

    private long idOf(SpotSource source, String externalId) {
        return jdbc.queryForObject("SELECT id FROM spots WHERE source = ? AND external_id = ?", Long.class,
                source.name(), externalId);
    }

    @Test
    void importsOnlyWhatIsMissingAndSkipsABrokenPlace() throws Exception {
        assertThat(importer.importAll()).isEqualTo(new Report(2, 4, 1));
        assertThat(importer.importAll()).isEqualTo(new Report(0, 0, 1));

        assertThat(spotRepository.count()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_scenes", Integer.class)).isEqualTo(4);
        Spot existing = spotRepository.findById(nami).orElseThrow();
        assertThat(existing.getName()).isEqualTo("남이섬");
        Spot added = spotRepository.findById(idOf(SpotSource.TOURAPI, "900002")).orElseThrow();
        assertThat(added.getCategory()).isEqualTo(SpotCategory.ATTRACTION);
        assertThat(added.getThumbnailUrl()).isEqualTo(PHOTO);
        // the nightly gallery sync picks it up: TourAPI, has a main photo, gallery never fetched
        assertThat(added.getGallerySyncedAt()).isNull();
    }

    @Test
    void spotDetailListsItsScenes() throws Exception {
        importer.importAll();

        mvc.perform(get("/api/spots/{id}", idOf(SpotSource.TOURAPI, "900002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenes", hasSize(2)))
                .andExpect(jsonPath("$.scenes[0].work").value("겨울연가"))
                .andExpect(jsonPath("$.scenes[0].kind").value("DRAMA"))
                .andExpect(jsonPath("$.scenes[0].scene").value("마지막 20화"))
                .andExpect(jsonPath("$.scenes[1].work").value("올드보이"))
                .andExpect(jsonPath("$.scenes[1].kind").value("MOVIE"));
        mvc.perform(get("/api/spots/{id}", nami)).andExpect(jsonPath("$.scenes", hasSize(1)));
    }

    @Test
    void worksAreCountedWithACover() throws Exception {
        importer.importAll();

        mvc.perform(get("/api/scenes/works"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("겨울연가"))
                .andExpect(jsonPath("$[0].spotCount").value(3))
                .andExpect(jsonPath("$[0].coverUrl").value(PHOTO))
                .andExpect(jsonPath("$[1].title").value("올드보이"))
                .andExpect(jsonPath("$[1].kind").value("MOVIE"));
    }

    @Test
    void aWorkListsItsSpotsPhotoFirst() throws Exception {
        importer.importAll();

        mvc.perform(get("/api/scenes").param("work", "겨울연가"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work.title").value("겨울연가"))
                .andExpect(jsonPath("$.work.spotCount").value(3))
                .andExpect(jsonPath("$.work.coverUrl").value(PHOTO))
                .andExpect(jsonPath("$.spots", hasSize(3)))
                .andExpect(jsonPath("$.spots[0].spot.name").value("외도 보타니아"))
                .andExpect(jsonPath("$.spots[0].scene").value("마지막 20화"))
                .andExpect(jsonPath("$.spots[1].spot.name").value("남이섬"))
                .andExpect(jsonPath("$.spots[2].spot.name").value("서강대교"))
                .andExpect(jsonPath("$.spots[2].spot.thumbnailUrl").value(nullValue()));
    }

    @Test
    void rejectsAnUnknownOrBadTitle() throws Exception {
        importer.importAll();

        mvc.perform(get("/api/scenes").param("work", "없는 드라마")).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenes").param("work", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("work must be 1 to 100 characters"));
        mvc.perform(get("/api/scenes").param("work", "가".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/scenes")).andExpect(status().isBadRequest());
    }

    @Test
    void scenesGoWithTheirSpot() throws Exception {
        importer.importAll();

        spotRepository.deleteById(idOf(SpotSource.TOURAPI, "900002"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_scenes", Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/scenes").param("work", "올드보이")).andExpect(status().isNotFound());
    }

    // the next start would import a deleted one again, so these change only in scenes.json
    @Test
    void anAdminCannotEditOrDeleteAFilmLocation() throws Exception {
        importer.importAll();
        User admin = userRepository.save(User.signUp("admin@example.com", "$2a$10$unused", "admin"));
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin.getId());
        String token = "Bearer " + tokenService.issue(userRepository.findById(admin.getId()).orElseThrow()).accessToken();

        long bridge = idOf(SpotSource.SCENE, "scene-1012");
        mvc.perform(delete("/api/admin/spots/{id}", bridge).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());
        assertThat(spotRepository.existsById(bridge)).isTrue();
        mvc.perform(get("/api/admin/spots").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/spots/{id}", bridge)).andExpect(jsonPath("$.manual").value(false));
    }
}
