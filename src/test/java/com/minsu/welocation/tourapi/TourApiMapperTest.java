package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.SpotCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();

    // shaped like a real areaBasedList2 item (KorService2, 2026-11-11)
    private ObjectNode item() {
        return (ObjectNode) json.readTree("""
                {"addr1": "부산광역시 해운대구 우동", "addr2": "", "contentid": "126081", "contenttypeid": "12",
                 "firstimage": "https://tong.visitkorea.or.kr/cms/resource/1.jpg", "cpyrhtDivCd": "Type3",
                 "mapx": "129.1586", "mapy": "35.1587", "title": "해운대해수욕장",
                 "lDongRegnCd": "26", "lDongSignguCd": "350", "lclsSystm3": "NA020900"}
                """);
    }

    private TourApiMapper.Result map(JsonNode item) {
        return TourApiMapper.map(item);
    }

    @Test
    void mapsAValidItem() {
        TourApiMapper.SpotData d = map(item()).data();

        assertThat(d.externalId()).isEqualTo("126081");
        assertThat(d.name()).isEqualTo("해운대해수욕장");
        assertThat(d.category()).isEqualTo(SpotCategory.BEACH);
        assertThat(d.region()).isEqualTo(Region.BUSAN);
        assertThat(d.latitude()).isEqualTo(35.1587);
        assertThat(d.longitude()).isEqualTo(129.1586);
        assertThat(d.imageUrl()).isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1.jpg");
    }

    @Test
    void splitsTheMergedJeonnamGwangjuCodeByDistrict() {
        assertThat(TourApiMapper.region("12", "210")).isEqualTo(Region.GWANGJU);
        assertThat(TourApiMapper.region("12", "330")).isEqualTo(Region.GWANGJU);
        assertThat(TourApiMapper.region("12", "850")).isEqualTo(Region.JEONNAM);
        assertThat(TourApiMapper.region("12", "110")).isEqualTo(Region.JEONNAM);
        assertThat(TourApiMapper.region("12", "")).isNull();
        // an unknown or reformatted district is skipped rather than guessed into Jeonnam
        assertThat(TourApiMapper.region("12", "12210")).isNull();
        assertThat(TourApiMapper.region("12", "999")).isNull();
        assertThat(TourApiMapper.region("36110", "")).isEqualTo(Region.SEJONG);
        assertThat(TourApiMapper.region("51", "150")).isEqualTo(Region.GANGWON);
        assertThat(TourApiMapper.region("99", "")).isNull();
        assertThat(TourApiMapper.region("", "")).isNull();
    }

    @Test
    void leisureCodesBecomeLeisure() {
        assertThat(map(item().put("lclsSystm3", "LS010300")).data().category()).isEqualTo(SpotCategory.LEISURE);
        assertThat(map(item().put("lclsSystm3", "VE020100")).data().category()).isEqualTo(SpotCategory.LEISURE);
        // 골프 is a leisure sport too, but not on the list
        assertThat(map(item().put("lclsSystm3", "LS010400")).skipReason()).isEqualTo("category");
    }

    @Test
    void skipsItemsOutsideTheWhitelistOrWithBadFields() {
        assertThat(map(item().put("lclsSystm3", "HS030100")).skipReason()).isEqualTo("category");
        assertThat(map(item().put("lDongRegnCd", "")).skipReason()).isEqualTo("region");
        assertThat(map(item().put("contentid", "12a")).skipReason()).isEqualTo("bad contentid");
        assertThat(map(item().put("title", " <b></b> ")).skipReason()).isEqualTo("no name");
        assertThat(map(item().put("addr1", "")).skipReason()).isEqualTo("no address");
        assertThat(map(item().put("mapx", "0").put("mapy", "0")).skipReason()).isEqualTo("coordinates");
        assertThat(map(item().put("mapx", "")).skipReason()).isEqualTo("coordinates");
        assertThat(map(item().put("mapy", "NaN")).skipReason()).isEqualTo("coordinates");
        // swapped x/y is a real mistake in public data; it lands outside Korea
        assertThat(map(item().put("mapx", "35.1587").put("mapy", "129.1586")).skipReason()).isEqualTo("coordinates");
    }

    @Test
    void dropsImagesThatAreNotHttpsOrNotOpenLicensed() {
        assertThat(map(item().put("firstimage", "http://tong.visitkorea.or.kr/a.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "javascript:alert(1)")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://a.kr/x y.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("cpyrhtDivCd", "")).data().imageUrl()).isNull();
        assertThat(map(item().put("cpyrhtDivCd", "Type4")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://evil.example/track.gif?u=1")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://tong.visitkorea.or.kr.evil.example/a.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://x@tong.visitkorea.or.kr/a.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://tong.visitkorea.or.kr:8443/a.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("firstimage", "https://tong.visitkorea.or.kr/a\u202e.jpg")).data().imageUrl()).isNull();
        assertThat(map(item().put("cpyrhtDivCd", "Type1")).data().imageUrl()).isNotNull();
    }

    @Test
    void cleansTextFromTheSource() {
        TourApiMapper.SpotData d = map(item()
                .put("title", "<b>카페</b>&amp;바다 &lt;script&gt;x&lt;/script&gt;​\u0007   끝")
                .put("addr2", " (우동)")).data();

        assertThat(d.name()).isEqualTo("카페 &바다 x 끝");
        assertThat(d.address()).isEqualTo("부산광역시 해운대구 우동 (우동)");
        assertThat(map(item().put("title", "카페 <img src=x onerror=alert(1)")).data().name()).isEqualTo("카페");
    }

    @Test
    void acceptsBothKeyFormsFromDataGoKr() {
        assertThat(TourApiClient.requestKey("ab+c/d==")).isEqualTo("ab+c/d==");
        assertThat(TourApiClient.requestKey("ab%2Bc%2Fd%3D%3D")).isEqualTo("ab+c/d==");
        assertThat(TourApiClient.requestKey(" abc \n")).isEqualTo("abc");
    }

    @Test
    void cutsLongTextByCharactersNotBytes() {
        String name = "가".repeat(199) + "😀😀";
        String cut = map(item().put("title", name)).data().name();

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(200);
        assertThat(cut).endsWith("😀");
    }

    @Test
    void keywordItemsNeedThePlaceInTheTitle() {
        TourApiMapper.Keyword archery = TourApiMapper.KEYWORDS.get("양궁");

        ObjectNode range = item().put("title", "예천 진호국제양궁장").put("lclsSystm3", "VE100100");
        assertThat(TourApiMapper.mapKeyword(range, archery).data().category()).isEqualTo(SpotCategory.LEISURE);

        ObjectNode unrelated = item().put("title", "양궁 체험 카페").put("lclsSystm3", "VE100100");
        assertThat(TourApiMapper.mapKeyword(unrelated, archery).skipReason()).isEqualTo("keyword title");

        // a code we sync anyway keeps its own category
        ObjectNode beach = item().put("title", "양궁장 해변");
        assertThat(TourApiMapper.mapKeyword(beach, archery).data().category()).isEqualTo(SpotCategory.BEACH);
    }
}
