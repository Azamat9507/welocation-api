package com.minsu.welocation.tourapi;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

// Runs the sync against a local stand-in for TourAPI that serves recorded items
// (src/test/resources/tourapi/items.json, from areaBasedList2 on 2026-11-11), sliced into pages
// the way the real API does. The real API is never called from tests.
@SpringBootTest
@Import(TestcontainersConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class TourApiSyncTest {

    // base64-like on purpose: data.go.kr "Decoding" keys carry + / =, which must survive URL encoding
    static final String KEY = "fake+key/f00d=cafe0123456789ab==";
    static final int ITEMS = 25;
    static final int PAGE_SIZE = 2;
    static final JsonMapper JSON = JsonMapper.builder().build();

    static HttpServer server;
    static Map<String, List<ObjectNode>> fixture;
    static final List<Map<String, String>> requests = Collections.synchronizedList(new ArrayList<>());
    // per-test override; return null to fall through to the recorded data
    static volatile Function<Map<String, String>, Answer> override;

    record Answer(int status, String body, long delayMillis, long stallBodyMillis) {
        Answer(int status, String body, long delayMillis) {
            this(status, body, delayMillis, 0);
        }
    }

    @DynamicPropertySource
    static void tourApi(DynamicPropertyRegistry registry) throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", TourApiSyncTest::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }
        registry.add("welocation.tourapi.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        registry.add("welocation.tourapi.key", () -> KEY);
        registry.add("welocation.tourapi.page-size", () -> PAGE_SIZE);
        registry.add("welocation.tourapi.connect-timeout", () -> "1s");
        registry.add("welocation.tourapi.read-timeout", () -> "1s");
        registry.add("welocation.tourapi.retry-backoff", () -> "10ms");
    }

    @Autowired
    TourApiSyncService sync;

    @Autowired
    SpotRepository spotRepository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TourApiClient client;

    @Autowired
    TourApiSpotRepository tourApiSpots;

    @Autowired
    TourApiProperties props;

    TourApiProperties withWatersideKm(double km) {
        return new TourApiProperties(props.baseUrl(), props.key(), props.pageSize(), props.connectTimeout(),
                props.readTimeout(), props.maxAttempts(), props.retryBackoff(), props.galleryPerNight(),
                props.galleryMaxImages(), km);
    }

    @BeforeEach
    void loadFixture() throws IOException {
        fixture = new HashMap<>(read("/tourapi/items.json"));
        requests.clear();
        override = null;
    }

    static Map<String, List<ObjectNode>> read(String resource) throws IOException {
        try (InputStream in = TourApiSyncTest.class.getResourceAsStream(resource)) {
            JsonNode root = JSON.readTree(in);
            Map<String, List<ObjectNode>> m = new HashMap<>();
            for (String code : root.propertyNames()) {
                List<ObjectNode> items = new ArrayList<>();
                root.get(code).values().forEach(n -> items.add((ObjectNode) n));
                m.put(code, items);
            }
            return m;
        }
    }

    // Invented 숙박 items (stays.json), placed around recorded water spots: a pension ~300 m north of 강문해변,
    // a hotel ~330 m north of the 한강 point, a hotel ~5 km south of 강문해변, a hanok stay without coordinates.
    // Not part of the default fixture, so the other tests see the stay codes answer with no items.
    static void loadStays() throws IOException {
        fixture.putAll(read("/tourapi/stays.json"));
    }

    @AfterEach
    void cleanUp() {
        override = null;
        spotRepository.deleteAll();
    }

    static void handle(HttpExchange ex) throws IOException {
        Map<String, String> q = new HashMap<>();
        for (String pair : ex.getRequestURI().getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        requests.add(q);
        Function<Map<String, String>, Answer> o = override;
        Answer a = o == null ? null : o.apply(q);
        if (a == null) {
            a = new Answer(200, q.containsKey("contentId") ? gallery() : page(q), 0);
        }
        try {
            if (a.delayMillis() > 0) {
                Thread.sleep(a.delayMillis());
            }
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(a.status(), bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                if (a.stallBodyMillis() > 0) {
                    out.write(bytes, 0, 10);
                    out.flush();
                    Thread.sleep(a.stallBodyMillis());
                }
                out.write(bytes, a.stallBodyMillis() > 0 ? 10 : 0, bytes.length - (a.stallBodyMillis() > 0 ? 10 : 0));
            }
        } catch (IOException | InterruptedException e) {
            // the client gave up first (timeout tests)
        } finally {
            ex.close();
        }
    }

    static String page(Map<String, String> q) {
        List<ObjectNode> all = fixture.getOrDefault(q.get("lclsSystm3"), List.of());
        int size = Integer.parseInt(q.get("numOfRows"));
        int pageNo = Integer.parseInt(q.get("pageNo"));
        List<ObjectNode> slice = all.subList(Math.min(all.size(), (pageNo - 1) * size), Math.min(all.size(), pageNo * size));
        ObjectNode root = JSON.createObjectNode();
        ObjectNode response = root.putObject("response");
        response.putObject("header").put("resultCode", "0000").put("resultMsg", "OK");
        ObjectNode body = response.putObject("body");
        if (slice.isEmpty()) {
            body.put("items", "");
        } else {
            ArrayNode arr = body.putObject("items").putArray("item");
            slice.forEach(arr::add);
        }
        body.put("numOfRows", size).put("pageNo", pageNo).put("totalCount", all.size());
        return JSON.writeValueAsString(root);
    }

    // detailImage2 for 완도타워 (632089), recorded 2026-11-12: 15 photos, Type1 and Type3. Served for any contentId.
    static String gallery() {
        try (InputStream in = TourApiSyncTest.class.getResourceAsStream("/tourapi/detail-images-632089.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static long galleryRequests() {
        synchronized (requests) {
            return requests.stream().filter(q -> q.containsKey("contentId")).count();
        }
    }

    static long requestsFor(String code) {
        synchronized (requests) {
            return requests.stream().filter(q -> code.equals(q.get("lclsSystm3"))).count();
        }
    }

    private Spot byExternalId(String id) {
        return spotRepository.findAll().stream()
                .filter(s -> s.getSource() == SpotSource.TOURAPI && id.equals(s.getExternalId()))
                .findFirst().orElseThrow();
    }

    @Test
    void firstRunCreatesAndSecondRunChangesNothing() {
        SyncReport first = sync.sync();

        assertThat(first.failed()).isEmpty();
        assertThat(first.skipped()).isEmpty();
        assertThat(first.created()).isEqualTo(ITEMS);
        assertThat(spotRepository.count()).isEqualTo(ITEMS);
        // 5 items at 2 per page is 3 pages for every category, plus one empty page for each of the 4 stay codes
        assertThat(requests).hasSize(5 * 3 + 4);
        assertThat(requests).allSatisfy(q -> assertThat(q.get("serviceKey")).isEqualTo(KEY));

        SyncReport second = sync.sync();

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(ITEMS);
        assertThat(spotRepository.count()).isEqualTo(ITEMS);
    }

    @Test
    void storesMappedFieldsAndOnlyLicensedImages() {
        sync.sync();

        tx.executeWithoutResult(s -> {
            Spot wando = byExternalId("632089");
            assertThat(wando.getName()).isEqualTo("완도타워");
            assertThat(wando.getCategory()).isEqualTo(SpotCategory.VIEWPOINT);
            assertThat(wando.getRegion()).isEqualTo(Region.JEONNAM);
            assertThat(wando.getThumbnailUrl()).startsWith("https://tong.visitkorea.or.kr/");
            assertThat(wando.getImages()).hasSize(1);

            // recorded with an empty cpyrhtDivCd and no image
            Spot noImage = byExternalId("631066");
            assertThat(noImage.getThumbnailUrl()).isNull();
            assertThat(noImage.getImages()).isEmpty();
        });
    }

    @Test
    void changedItemIsUpdatedInPlace() {
        sync.sync();
        Long id = byExternalId("125872").getId();

        ObjectNode lake = fixture.get("NA020200").stream()
                .filter(n -> n.get("contentid").asString().equals("125872")).findFirst().orElseThrow();
        lake.put("title", "충주호 (새 이름)");
        lake.put("firstimage", "https://tong.visitkorea.or.kr/cms/resource/new.jpg");

        SyncReport report = sync.sync();

        assertThat(report.updated()).isEqualTo(1);
        assertThat(report.unchanged()).isEqualTo(ITEMS - 1);
        assertThat(spotRepository.count()).isEqualTo(ITEMS);
        tx.executeWithoutResult(s -> {
            Spot spot = byExternalId("125872");
            assertThat(spot.getId()).isEqualTo(id);
            assertThat(spot.getName()).isEqualTo("충주호 (새 이름)");
            assertThat(spot.getThumbnailUrl()).endsWith("/new.jpg");
            assertThat(spot.getImages()).extracting("url").containsExactly(spot.getThumbnailUrl());
        });
    }

    @Test
    void neverRemovesSpotsTheSourceNoLongerLists() {
        // same id as a recorded TourAPI item: only the source tells them apart
        Spot manual = spotRepository.save(new Spot("직접 등록 카페", SpotCategory.CAFE, Region.BUSAN, "부산", 35.1, 129.1,
                SpotSource.MANUAL, "632089"));
        Spot gone = spotRepository.save(new Spot("사라진 해변", SpotCategory.BEACH, Region.JEJU, "제주", 33.5, 126.5,
                SpotSource.TOURAPI, "999999999"));

        sync.sync();

        assertThat(spotRepository.count()).isEqualTo(ITEMS + 2);
        Spot manualAfter = spotRepository.findById(manual.getId()).orElseThrow();
        assertThat(manualAfter.getName()).isEqualTo("직접 등록 카페");
        assertThat(manualAfter.getCategory()).isEqualTo(SpotCategory.CAFE);
        assertThat(manualAfter.getLatitude()).isEqualTo(35.1);
        assertThat(spotRepository.findById(gone.getId())).get().extracting(Spot::getName).isEqualTo("사라진 해변");
    }

    @Test
    void retriesServerErrorsAndThenSucceeds() {
        int[] calls = {0};
        override = q -> q.get("lclsSystm3").equals("FD050100") && q.get("pageNo").equals("1") && calls[0]++ < 2
                ? new Answer(503, "busy", 0) : null;

        SyncReport report = sync.sync();

        assertThat(report.failed()).isEmpty();
        assertThat(report.created()).isEqualTo(ITEMS);
        assertThat(requestsFor("FD050100")).isEqualTo(2 + 3);
    }

    @Test
    void oneBrokenCategoryDoesNotStopTheOthers() {
        override = q -> q.get("lclsSystm3").equals("FD050100") ? new Answer(500, "down", 0) : null;

        SyncReport report = sync.sync();

        assertThat(report.failed()).containsOnlyKeys("FD050100");
        assertThat(report.failed().get("FD050100")).contains("gave up after 3 attempts, last HTTP 500");
        assertThat(report.created()).isEqualTo(ITEMS - 5);
        assertThat(requestsFor("FD050100")).isEqualTo(3);
    }

    @Test
    void gatewayAndResultCodeErrorsAreNotRetried() {
        override = q -> switch (q.get("lclsSystm3")) {
            case "NA020900" -> new Answer(200, """
                    <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
                    <returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>
                    <returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>""", 0);
            case "VE010200" -> new Answer(200,
                    "{\"response\":{\"header\":{\"resultCode\":\"10\",\"resultMsg\":\"INVALID_REQUEST_PARAMETER_ERROR\"}}}", 0);
            case "NA020100" -> new Answer(200, "{\"response\":", 0);
            default -> null;
        };

        SyncReport report = sync.sync();

        assertThat(report.failed()).containsOnlyKeys("NA020900", "VE010200", "NA020100");
        assertThat(report.failed().get("NA020900")).contains("returnReasonCode 22");
        assertThat(report.failed().get("VE010200")).contains("resultCode 10");
        assertThat(report.failed().get("NA020100")).contains("broken JSON");
        assertThat(requestsFor("NA020900")).isEqualTo(1);
        assertThat(requestsFor("VE010200")).isEqualTo(1);
        assertThat(requestsFor("NA020100")).isEqualTo(1);
        assertThat(report.created()).isEqualTo(10);
    }

    @Test
    void aHangingServerIsCutOffByTheReadTimeout() {
        override = q -> q.get("lclsSystm3").equals("NA020200") ? new Answer(200, page(q), 30_000) : null;

        long start = System.nanoTime();
        SyncReport report = sync.sync();
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertThat(report.failed()).containsOnlyKeys("NA020200");
        assertThat(report.failed().get("NA020200")).contains("gave up after 3 attempts, last I/O");
        // 3 attempts x 1 s read timeout, plus a little
        assertThat(seconds).isLessThan(8);
        assertThat(report.created()).isEqualTo(ITEMS - 5);
    }

    @Test
    void aBodyThatStallsAfterTheHeadersIsRetriedAndThenGivenUp() {
        override = q -> q.get("lclsSystm3").equals("NA020100") ? new Answer(200, page(q), 0, 30_000) : null;

        SyncReport report = sync.sync();

        assertThat(report.failed()).containsOnlyKeys("NA020100");
        assertThat(report.failed().get("NA020100")).contains("gave up after 3 attempts");
        assertThat(requestsFor("NA020100")).isEqualTo(3);
        assertThat(report.created()).isEqualTo(ITEMS - 5);
    }

    @Test
    void itemsMissingWhileTotalCountSaysOtherwiseIsAFailure() {
        override = q -> q.get("lclsSystm3").equals("NA020200")
                ? new Answer(200, "{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\",\"totalCount\":58}}}", 0)
                : null;

        SyncReport report = sync.sync();

        assertThat(report.failed()).containsOnlyKeys("NA020200");
        assertThat(report.failed().get("NA020200")).contains("but no items");
    }

    @Test
    void theKeyNeverShowsUpInLogsOrTheReport(CapturedOutput output) {
        // every failure path at once, and an upstream message that echoes the key back
        override = q -> switch (q.get("lclsSystm3")) {
            case "FD050100" -> new Answer(500, "error for serviceKey=" + KEY, 0);
            case "NA020900" -> new Answer(200, "<returnReasonCode>30</returnReasonCode> " + KEY, 0);
            case "NA020200" -> new Answer(200, page(q), 30_000);
            case "NA020100" -> new Answer(403, "forbidden " + KEY, 0);
            case "VE010200" -> new Answer(200,
                    "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"bad key " + KEY + "\"}}}", 0);
            default -> null;
        };

        SyncReport report = sync.sync();

        assertThat(report.failed()).hasSize(5);
        assertThat(report.failed().get("VE010200")).contains("bad key ***");
        assertThat(report.toString() + report.failed()).doesNotContain(KEY);
        assertThat(output.getAll()).contains("TourAPI sync of FD050100 stopped").doesNotContain(KEY);
    }

    @Test
    void aSecondRunWhileOneIsGoingIsSkipped() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        override = q -> {
            if (q.get("lclsSystm3").equals("FD050100") && q.get("pageNo").equals("1") && arrived.getCount() > 0) {
                arrived.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        };
        CompletableFuture<SyncReport> first = CompletableFuture.supplyAsync(sync::sync);
        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();

        SyncReport second = sync.sync();
        release.countDown();

        assertThat(second.alreadyRunning()).isTrue();
        assertThat(second.created()).isZero();
        assertThat(first.get(10, TimeUnit.SECONDS).created()).isEqualTo(ITEMS);
    }

    @Test
    void galleryKeepsTheMainPhotoFirstAndIsFetchedOnce() {
        sync.sync();
        long withPhoto = spotRepository.findAll().stream().filter(sp -> sp.getThumbnailUrl() != null).count();

        GalleryReport first = sync.syncGalleries(100);

        // spots without a main photo are not asked about: TourAPI has no gallery for them
        assertThat(galleryRequests()).isEqualTo(withPhoto);
        assertThat(requests).filteredOn(q -> q.containsKey("contentId"))
                .allSatisfy(q -> assertThat(q.get("serviceKey")).isEqualTo(KEY));
        assertThat(first.checked()).isEqualTo((int) withPhoto);
        assertThat(first.failed()).isZero();
        tx.executeWithoutResult(st -> {
            Spot wando = byExternalId("632089");
            // main photo + 9 of the 15 recorded ones (gallery-max-images 10 by default)
            assertThat(wando.getImages()).hasSize(10);
            assertThat(wando.getImages().getFirst().getUrl()).isEqualTo(wando.getThumbnailUrl());
            assertThat(wando.getImages()).extracting("sortOrder").containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
            assertThat(wando.getImages()).extracting("url").doesNotHaveDuplicates()
                    .allSatisfy(u -> assertThat((String) u).startsWith("https://tong.visitkorea.or.kr/"));
            assertThat(byExternalId("631066").getImages()).isEmpty();
        });

        requests.clear();
        GalleryReport second = sync.syncGalleries(100);
        assertThat(second.checked()).isZero();
        assertThat(galleryRequests()).isZero();

        // the list sync seeing the same main photo leaves the gallery alone
        sync.sync();
        tx.executeWithoutResult(st -> assertThat(byExternalId("632089").getImages()).hasSize(10));
    }

    @Test
    void galleryStartsWithTheSpotsTheHomePageShowsFirst() {
        sync.sync();
        List<Long> newestWithPhoto = spotRepository.findAll().stream()
                .filter(sp -> sp.getThumbnailUrl() != null)
                .map(Spot::getId)
                .sorted(Collections.reverseOrder())
                .limit(2)
                .toList();

        sync.syncGalleries(2);

        List<Long> fetched = spotRepository.findAll().stream()
                .filter(sp -> sp.getGallerySyncedAt() != null)
                .map(Spot::getId)
                .sorted(Collections.reverseOrder())
                .toList();
        assertThat(fetched).isEqualTo(newestWithPhoto);
    }

    @Test
    void galleryDropsUnlicensedAndForeignPhotosAndTheMainPhotoAgain() {
        sync.sync();
        String mainPhoto = byExternalId("632089").getThumbnailUrl();
        override = q -> q.containsKey("contentId") ? new Answer(200, """
                {"response":{"header":{"resultCode":"0000","resultMsg":"OK"},"body":{"items":{"item":[
                  {"originimgurl":"https://tong.visitkorea.or.kr/cms/resource/1/ok_1.jpg","cpyrhtDivCd":"Type3"},
                  {"originimgurl":"https://tong.visitkorea.or.kr/cms/resource/1/no_license.jpg","cpyrhtDivCd":"Type2"},
                  {"originimgurl":"https://evil.example.com/x.jpg","cpyrhtDivCd":"Type1"},
                  {"originimgurl":"http://tong.visitkorea.or.kr/cms/resource/1/plain_http.jpg","cpyrhtDivCd":"Type1"},
                  {"originimgurl":"https://tong.visitkorea.or.kr/cms/resource/1/ok_1.jpg","cpyrhtDivCd":"Type3"},
                  {"originimgurl":"%s","cpyrhtDivCd":"Type1"}
                ]},"numOfRows":50,"pageNo":1,"totalCount":6}}}""".formatted(mainPhoto), 0) : null;

        sync.syncGalleries(100);

        tx.executeWithoutResult(st -> assertThat(byExternalId("632089").getImages()).extracting("url")
                .containsExactly(byExternalId("632089").getThumbnailUrl(),
                        "https://tong.visitkorea.or.kr/cms/resource/1/ok_1.jpg"));
    }

    // what data.go.kr sends with HTTP 200 once the daily quota is used up
    static final String QUOTA_EXCEEDED = """
            <OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>
            <returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>""";

    @Test
    void galleryStopsAfterThreeServiceFailuresInARowAndRetriesThoseSpotsLater() {
        sync.sync();
        override = q -> q.containsKey("contentId") ? new Answer(200, QUOTA_EXCEEDED, 0) : null;

        GalleryReport report = sync.syncGalleries(100);

        assertThat(report.failed()).isEqualTo(3);
        assertThat(report.checked()).isZero();
        // one request per spot, no retries for galleries
        assertThat(galleryRequests()).isEqualTo(3);

        override = null;
        requests.clear();
        long withPhoto = spotRepository.findAll().stream().filter(sp -> sp.getThumbnailUrl() != null).count();
        assertThat(sync.syncGalleries(100).checked()).isEqualTo((int) withPhoto);
    }

    @Test
    void galleryKeepsGoingWhenFailuresAreNotInARow() {
        sync.sync();
        AtomicInteger n = new AtomicInteger();
        // fail, fail, ok, fail, fail, ok ...
        override = q -> q.containsKey("contentId") && n.incrementAndGet() % 3 != 0 ? new Answer(500, "busy", 0) : null;
        long withPhoto = spotRepository.findAll().stream().filter(sp -> sp.getThumbnailUrl() != null).count();

        GalleryReport report = sync.syncGalleries(100);

        assertThat(galleryRequests()).isEqualTo(withPhoto);
        assertThat(report.checked() + report.failed()).isEqualTo((int) withPhoto);
    }

    @Test
    void spotWithAnUnusableAnswerIsMarkedSoItDoesNotBlockTheQueue() {
        sync.sync();
        AtomicInteger n = new AtomicInteger();
        // the first three spots always get an error answer for themselves, the rest are fine
        override = q -> q.containsKey("contentId") && n.incrementAndGet() <= 3 ? new Answer(200, """
                {"response":{"header":{"resultCode":"10","resultMsg":"INVALID_REQUEST_PARAMETER_ERROR"},"body":{}}}""", 0)
                : null;
        long withPhoto = spotRepository.findAll().stream().filter(sp -> sp.getThumbnailUrl() != null).count();

        GalleryReport report = sync.syncGalleries(100);

        assertThat(report.failed()).isEqualTo(3);
        assertThat(report.checked()).isEqualTo((int) withPhoto);
        requests.clear();
        assertThat(sync.syncGalleries(100).checked()).isZero();
        assertThat(galleryRequests()).isZero();
    }

    @Test
    void newMainPhotoMeansTheGalleryIsFetchedAgain() {
        sync.sync();
        sync.syncGalleries(100);
        ObjectNode tower = fixture.get("VE010200").stream()
                .filter(n -> n.get("contentid").asString().equals("632089")).findFirst().orElseThrow();
        tower.put("firstimage", "https://tong.visitkorea.or.kr/cms/resource/new_main.jpg");

        sync.sync();
        tx.executeWithoutResult(st -> assertThat(byExternalId("632089").getImages()).hasSize(1));
        requests.clear();
        GalleryReport again = sync.syncGalleries(100);

        assertThat(again.checked()).isEqualTo(1);
        tx.executeWithoutResult(st -> {
            Spot wando = byExternalId("632089");
            assertThat(wando.getImages().getFirst().getUrl()).endsWith("/new_main.jpg");
            assertThat(wando.getImages()).hasSize(10);
        });
    }

    @Test
    void staysAreKeptOnlyNearWaterAndASecondRunChangesNothing() throws IOException {
        loadStays();

        SyncReport first = sync.sync();

        assertThat(first.failed()).isEmpty();
        assertThat(first.created()).isEqualTo(ITEMS + 2);
        assertThat(first.skipped()).containsOnly(entry("not waterside", 1), entry("coordinates", 1));
        assertThat(spotRepository.findAll()).filteredOn(sp -> sp.getCategory() == SpotCategory.STAY)
                .extracting(Spot::getExternalId).containsExactlyInAnyOrder("9900101", "9900102");
        Spot pension = byExternalId("9900101");
        assertThat(pension.getName()).isEqualTo("강문 바다정원 펜션");
        assertThat(pension.getRegion()).isEqualTo(Region.GANGWON);
        // one request for each stay code: the water categories go first, the stays are checked against them
        assertThat(requestsFor("AC010100")).isEqualTo(1);
        assertThat(requestsFor("AC030100")).isEqualTo(1);
        assertThat(requests.indexOf(requests.stream().filter(q -> q.get("lclsSystm3").startsWith("AC"))
                .findFirst().orElseThrow())).isEqualTo(5 * 3);

        SyncReport second = sync.sync();

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(second.unchanged()).isEqualTo(ITEMS + 2);
        assertThat(second.skipped()).containsOnly(entry("not waterside", 1), entry("coordinates", 1));
        assertThat(spotRepository.count()).isEqualTo(ITEMS + 2);
    }

    @Test
    void waterDistanceIsAProperty() throws IOException {
        loadStays();
        // ~300 m and ~330 m from the water: both fail a 0.25 km limit
        TourApiSyncService strict = new TourApiSyncService(client, tourApiSpots, tx, withWatersideKm(0.25));

        SyncReport report = strict.sync();

        assertThat(report.skipped()).containsEntry("not waterside", 3);
        assertThat(spotRepository.findAll()).noneMatch(sp -> sp.getCategory() == SpotCategory.STAY);
    }

    @Test
    void onlyBeachesLakesAndRiversCountAsWater() throws IOException {
        loadStays();
        // ~300 m north of the café 커피친구 and ~6 km from 한강: a café or another stay nearby is not water
        ObjectNode condo = fixture.get("AC030100").getFirst().deepCopy();
        condo.put("contentid", "9900105");
        condo.put("title", "종로 카페거리 콘도");
        condo.put("mapy", "37.5752639352");
        condo.put("mapx", "126.9815062982");
        condo.put("lclsSystm2", "AC02");
        condo.put("lclsSystm3", "AC020100");
        fixture.put("AC020100", new ArrayList<>(List.of(condo)));

        SyncReport report = sync.sync();

        assertThat(report.skipped()).containsEntry("not waterside", 2);
        assertThat(spotRepository.findAll()).noneMatch(sp -> "9900105".equals(sp.getExternalId()));
    }

    @Test
    void aStayThatIsNoLongerNearWaterIsLeftAsItWas() throws IOException {
        loadStays();
        sync.sync();
        Spot before = byExternalId("9900101");
        ObjectNode pension = fixture.get("AC030100").getFirst();
        pension.put("title", "이사 간 펜션");
        pension.put("mapy", "37.7497975418");

        SyncReport report = sync.sync();

        assertThat(report.skipped()).containsEntry("not waterside", 2);
        Spot after = byExternalId("9900101");
        assertThat(after.getName()).isEqualTo("강문 바다정원 펜션");
        assertThat(after.getLatitude()).isEqualTo(before.getLatitude());
    }

    @Test
    void storedStaysGetAGalleryLikeOtherSpots() throws IOException {
        loadStays();
        sync.sync();

        sync.syncGalleries(100);

        assertThat(requests).filteredOn(q -> "9900101".equals(q.get("contentId"))).hasSize(1);
        // the far hotel has a photo too, but it was never stored
        assertThat(requests).noneMatch(q -> "9900103".equals(q.get("contentId")));
        tx.executeWithoutResult(st -> assertThat(byExternalId("9900101").getImages()).hasSize(10));
    }
}
