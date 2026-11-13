package com.minsu.welocation.spot;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ManualSpotApiTest {

    static final Path PHOTOS;
    static final HttpServer NOMINATIM;
    static final AtomicReference<String> lastGeocodeQuery = new AtomicReference<>();

    static {
        try {
            PHOTOS = Files.createTempDirectory("welocation-photos");
            NOMINATIM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // one hit in Suwon and one outside Korea, which must be dropped
        NOMINATIM.createContext("/search", exchange -> {
            lastGeocodeQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    [{"lat":"37.2853800","lon":"127.0214900","display_name":"창룡대로103번길, 팔달구, 수원시"},
                     {"lat":"48.85","lon":"2.35","display_name":"Paris"}]""".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        NOMINATIM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("welocation.photos.dir", PHOTOS::toString);
        registry.add("welocation.geocode.base-url", () -> "http://127.0.0.1:" + NOMINATIM.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        NOMINATIM.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TokenService tokenService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    String admin;
    String user;

    @BeforeEach
    void setUp() {
        admin = tokenFor("admin@example.com");
        user = tokenFor("user@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_likes");
        spotRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String tokenFor(String email) {
        User saved = userRepository.save(User.signUp(email, "$2a$10$notusedinthesetests", email.substring(0, email.indexOf('@'))));
        if (email.startsWith("admin")) {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", saved.getId());
            saved = userRepository.findById(saved.getId()).orElseThrow();
        }
        return "Bearer " + tokenService.issue(saved).accessToken();
    }

    private static String paintballField(String region) {
        return """
                {"name":"  화성 페인트볼 필드 ","category":"LEISURE","activity":"PAINTBALL",%s
                 "address":"경기 수원시 팔달구 창룡대로103번길 8","latitude":37.2854,"longitude":127.0215,
                 "description":"주말만 운영\\r\\n예약 필수"}""".formatted(region == null ? "" : "\"region\":\"" + region + "\",");
    }

    private long create(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/spots")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode upload(long spotId, byte[] bytes, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(multipart("/api/admin/spots/{id}/photos", spotId)
                        .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", bytes))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void anAdminAddsAPaintballFieldAndASearchInAnyLanguageFindsIt() throws Exception {
        long id = create(paintballField(null));

        mvc.perform(get("/api/spots/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("화성 페인트볼 필드"))
                // worked out from 경기 at the start of the address
                .andExpect(jsonPath("$.region").value("GYEONGGI"))
                .andExpect(jsonPath("$.activity").value("PAINTBALL"))
                .andExpect(jsonPath("$.manual").value(true))
                .andExpect(jsonPath("$.description").value("주말만 운영\n예약 필수"));

        for (String q : new String[]{"paintball", "Paint Ball", "пейнтбол", "peyntbol"}) {
            mvc.perform(get("/api/spots").param("q", q))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].activity").value("PAINTBALL"));
        }
        mvc.perform(get("/api/spots").param("activity", "PAINTBALL")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/spots").param("activity", "KART")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/spots").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void aTourApiSurvivalGameAlsoAnswersAPaintballSearch() throws Exception {
        Spot survival = new Spot("레이저 태그 서바이벌", SpotCategory.LEISURE, Region.CHUNGNAM, "충남 예산군", 36.6, 126.6,
                SpotSource.TOURAPI, "3079995");
        survival.setActivity(Activity.SURVIVAL);
        spotRepository.save(survival);

        mvc.perform(get("/api/spots").param("q", "paintball"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].activity").value("SURVIVAL"));
        // a plain word is still only a name or address search
        mvc.perform(get("/api/spots").param("q", "paint")).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void onlyLeisureSpotsKeepAnActivity() throws Exception {
        long id = create(paintballField(null).replace("\"LEISURE\"", "\"CAFE\""));
        mvc.perform(get("/api/spots/{id}", id)).andExpect(jsonPath("$.activity").doesNotExist());
    }

    @Test
    void anAddressWithoutAProvinceNeedsARegion() throws Exception {
        String noProvince = paintballField(null).replace("경기 수원시 팔달구", "팔달구");
        mvc.perform(post("/api/admin/spots").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(noProvince))
                .andExpect(status().isBadRequest());
        create(paintballField("SEOUL").replace("경기 수원시 팔달구", "팔달구"));
    }

    @Test
    void coordinatesOutsideKoreaAreRefused() throws Exception {
        mvc.perform(post("/api/admin/spots").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paintballField(null).replace("37.2854", "48.85")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void visitorsCannotUseAnyAdminEndpoint() throws Exception {
        long id = create(paintballField(null));
        for (String token : new String[]{user, null}) {
            int expected = token == null ? 401 : 403;
            var create = post("/api/admin/spots").contentType(MediaType.APPLICATION_JSON).content(paintballField(null));
            var update = put("/api/admin/spots/{id}", id).contentType(MediaType.APPLICATION_JSON).content(paintballField(null));
            var remove = delete("/api/admin/spots/{id}", id);
            var photo = multipart("/api/admin/spots/{id}/photos", id)
                    .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpeg(10, 10, false)));
            var geocode = get("/api/admin/geocode").param("q", "수원");
            var list = get("/api/admin/spots");
            for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                    create, update, remove, geocode, list}) {
                if (token != null) {
                    request.header(HttpHeaders.AUTHORIZATION, token);
                }
                mvc.perform(request).andExpect(status().is(expected));
            }
            if (token != null) {
                photo.header(HttpHeaders.AUTHORIZATION, token);
            }
            mvc.perform(photo).andExpect(status().is(expected));
        }
        assertThat(spotRepository.count()).isEqualTo(1);
        assertThat(photoRows(id)).isZero();
    }

    @Test
    void tourApiSpotsCannotBeChangedHere() throws Exception {
        Spot synced = spotRepository.save(new Spot("카트랜드", SpotCategory.LEISURE, Region.GYEONGGI, "경기", 37.5, 127.0,
                SpotSource.TOURAPI, "123"));
        mvc.perform(put("/api/admin/spots/{id}", synced.getId()).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(paintballField(null)))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/spots/{id}", synced.getId()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
        upload(synced.getId(), jpeg(10, 10, false), 409);
        assertThat(spotRepository.findById(synced.getId()).orElseThrow().getName()).isEqualTo("카트랜드");
    }

    @Test
    void anUploadedPhotoIsShrunkStrippedOfMetadataAndServed() throws Exception {
        long id = create(paintballField(null));

        JsonNode detail = upload(id, jpeg(2400, 1200, true), 200);

        String url = detail.path("images").get(0).asString();
        assertThat(url).matches("/photos/[0-9a-f]{32}\\.jpg");
        MvcResult served = mvc.perform(get("/api/spots" + url))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("immutable")))
                .andReturn();
        byte[] bytes = served.getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(800);
        // the uploaded file carried an Exif block with a position in it; none of it may survive
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("Exif").doesNotContain("GPS");
        mvc.perform(get("/api/spots").param("q", "페인트볼"))
                .andExpect(jsonPath("$.items[0].thumbnailUrl").value(url));
    }

    @Test
    void anythingButAJpegOrPngIsRefused() throws Exception {
        long id = create(paintballField(null));
        upload(id, "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8), 400);
        upload(id, new byte[0], 400);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "gif", gif);
        upload(id, gif.toByteArray(), 400);
        assertThat(photoRows(id)).isZero();
        try (var files = Files.list(PHOTOS)) {
            assertThat(files.filter(f -> f.toString().endsWith(".tmp"))).isEmpty();
        }
    }

    @Test
    void removingAPhotoDeletesTheFileAndTheNextPhotoBecomesTheMainOne() throws Exception {
        long id = create(paintballField(null));
        upload(id, jpeg(20, 20, false), 200);
        JsonNode detail = upload(id, png(), 200);
        String first = detail.path("images").get(0).asString();
        String second = detail.path("images").get(1).asString();

        mvc.perform(delete("/api/admin/spots/{id}/photos/{name}", id, first.substring("/photos/".length()))
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images.length()").value(1))
                .andExpect(jsonPath("$.images[0]").value(second));

        assertThat(Files.exists(PHOTOS.resolve(first.substring("/photos/".length())))).isFalse();
        mvc.perform(get("/api/spots" + first)).andExpect(status().isNotFound());
        mvc.perform(get("/api/spots").param("q", "페인트볼")).andExpect(jsonPath("$.items[0].thumbnailUrl").value(second));
    }

    @Test
    void aPhotoOverFiveMegabytesIsABadRequest() throws Exception {
        long id = create(paintballField(null));
        upload(id, new byte[5 * 1024 * 1024 + 1], 400);
    }

    @Test
    void photoNamesCannotReachOutsideTheFolder() throws Exception {
        Files.writeString(PHOTOS.resolveSibling("secret.txt"), "x");
        mvc.perform(get("/api/spots/photos/..%2Fsecret.txt")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/spots/photos/secret.txt")).andExpect(status().isNotFound());
    }

    @Test
    void aSpotSomeoneLikedIsNotDeletedButAnUntouchedOneIsWithItsPhotos() throws Exception {
        long liked = create(paintballField(null));
        long fresh = create(paintballField(null));
        String photo = upload(fresh, jpeg(20, 20, false), 200).path("images").get(0).asString();
        long userId = userRepository.findAll().stream().filter(u -> u.getEmail().startsWith("user")).findFirst()
                .orElseThrow().getId();
        jdbc.update("INSERT INTO spot_likes (user_id, spot_id, created_at) VALUES (?, ?, NOW(6))", userId, liked);

        mvc.perform(delete("/api/admin/spots/{id}", liked).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/spots/{id}", fresh).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(spotRepository.existsById(liked)).isTrue();
        assertThat(spotRepository.existsById(fresh)).isFalse();
        assertThat(Files.exists(PHOTOS.resolve(photo.substring("/photos/".length())))).isFalse();
    }

    @Test
    void geocodingKeepsOnlyPlacesInKorea() throws Exception {
        mvc.perform(get("/api/admin/geocode").param("q", "경기 수원시 팔달구 창룡대로103번길 8")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].latitude").value(37.28538))
                .andExpect(jsonPath("$[0].label").value("창룡대로103번길, 팔달구, 수원시"));
        assertThat(lastGeocodeQuery.get()).contains("countrycodes=kr");
        mvc.perform(get("/api/admin/geocode").param("q", " ").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    private int photoRows(long spotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM spot_images WHERE spot_id = ?", Integer.class, spotId);
    }

    // a JPEG, optionally with an APP1 Exif segment holding a GPS tag name, as a phone would write
    private static byte[] jpeg(int width, int height, boolean withExif) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        byte[] plain = out.toByteArray();
        if (!withExif) {
            return plain;
        }
        byte[] payload = "Exif\0\0GPSLatitude 37.2854 GPSLongitude 127.0215".getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteArrayOutputStream withApp1 = new ByteArrayOutputStream();
        withApp1.write(plain, 0, 2);
        withApp1.write(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        withApp1.write(payload);
        withApp1.write(plain, 2, plain.length - 2);
        return withApp1.toByteArray();
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
