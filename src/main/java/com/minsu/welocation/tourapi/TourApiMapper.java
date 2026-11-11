package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.SpotCategory;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// Turns one TourAPI item into the fields we store, or says why it was dropped. Everything in the
// item is treated as untrusted input.
final class TourApiMapper {

    // lclsSystm3 codes we sync, from lclsSystmCode2 (2026-11-11). ATTRACTION is left out on purpose.
    static final Map<String, SpotCategory> CATEGORIES;

    static {
        Map<String, SpotCategory> m = new LinkedHashMap<>();
        m.put("FD050100", SpotCategory.CAFE);
        m.put("NA020900", SpotCategory.BEACH);
        m.put("NA020200", SpotCategory.LAKE);
        m.put("NA020100", SpotCategory.RIVER);
        m.put("VE010200", SpotCategory.VIEWPOINT);
        CATEGORIES = Map.copyOf(m);
    }

    // 공공누리 type 1 and 3 allow reuse with attribution; anything else (or no type) we don't store
    private static final Set<String> IMAGE_LICENSES = Set.of("Type1", "Type3");

    private static final String IMAGE_HOST = "tong.visitkorea.or.kr";

    private static final double MIN_LAT = 33.0, MAX_LAT = 39.0, MIN_LNG = 124.0, MAX_LNG = 132.5;

    record SpotData(String externalId, String name, SpotCategory category, Region region, String address,
                    double latitude, double longitude, String imageUrl) {
    }

    record Result(SpotData data, String skipReason) {
        static Result skip(String reason) {
            return new Result(null, reason);
        }
    }

    private TourApiMapper() {
    }

    static Result map(JsonNode item) {
        String contentId = item.path("contentid").asString("").strip();
        if (!contentId.matches("\\d{1,20}")) {
            return Result.skip("bad contentid");
        }
        SpotCategory category = CATEGORIES.get(item.path("lclsSystm3").asString(""));
        if (category == null) {
            return Result.skip("category");
        }
        Region region = region(item.path("lDongRegnCd").asString(""), item.path("lDongSignguCd").asString(""));
        if (region == null) {
            return Result.skip("region");
        }
        String name = clean(item.path("title").asString(""), 200);
        if (name.isEmpty()) {
            return Result.skip("no name");
        }
        String address = clean((item.path("addr1").asString("") + " " + item.path("addr2").asString("")), 300);
        if (address.isEmpty()) {
            return Result.skip("no address");
        }
        double lat = number(item.path("mapy").asString(""));
        double lng = number(item.path("mapx").asString(""));
        if (!(lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG)) {
            return Result.skip("coordinates");
        }
        return new Result(new SpotData(contentId, name, category, region, address, lat, lng, image(item)), null);
    }

    // lDongRegnCd is the legal-dong province code. 12 is the merged Jeonnam-Gwangju special city;
    // its five 구 districts (signgu 210-330) are the old Gwangju. 29/46/42/45 are the codes from
    // before the 2023-2026 renames, kept in case older items still carry them.
    static Region region(String regn, String signgu) {
        return switch (regn.strip()) {
            case "11" -> Region.SEOUL;
            case "26" -> Region.BUSAN;
            case "27" -> Region.DAEGU;
            case "28" -> Region.INCHEON;
            case "29" -> Region.GWANGJU;
            case "30" -> Region.DAEJEON;
            case "31" -> Region.ULSAN;
            case "36", "36110" -> Region.SEJONG;
            case "41" -> Region.GYEONGGI;
            case "42", "51" -> Region.GANGWON;
            case "43" -> Region.CHUNGBUK;
            case "44" -> Region.CHUNGNAM;
            case "45", "52" -> Region.JEONBUK;
            case "46" -> Region.JEONNAM;
            case "47" -> Region.GYEONGBUK;
            case "48" -> Region.GYEONGNAM;
            case "50" -> Region.JEJU;
            // codes from ldongCode2?lDongRegnCd=12 (2026-11-11); an unknown one is skipped, not guessed
            case "12" -> switch (signgu.strip()) {
                case "210", "240", "270", "300", "330" -> Region.GWANGJU;
                case "110", "130", "150", "170", "190", "710", "720", "730", "740", "750", "760", "770", "780",
                     "790", "800", "810", "820", "830", "840", "850", "860", "870" -> Region.JEONNAM;
                default -> null;
            };
            default -> null;
        };
    }

    // Only KTO's own image host: the 공공누리 license covers their photos, and any other host would
    // be a third party seeing every visitor's IP.
    private static String image(JsonNode item) {
        String url = item.path("firstimage").asString("").strip();
        if (url.length() > 500 || !url.chars().allMatch(c -> c > ' ' && c < 0x7f)
                || !IMAGE_LICENSES.contains(item.path("cpyrhtDivCd").asString(""))) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || !IMAGE_HOST.equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getUserInfo() != null) {
                return null;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return url;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // entities first so an escaped tag can't survive as a real one, then tags, control and
    // invisible characters, then whitespace
    static String clean(String raw, int maxCodePoints) {
        String s = HtmlUtils.htmlUnescape(raw)
                .replaceAll("<[^>]*(>|$)", " ")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ")
                .replaceAll("[\\s\\p{Z}]+", " ")
                .strip();
        if (s.codePointCount(0, s.length()) > maxCodePoints) {
            s = s.substring(0, s.offsetByCodePoints(0, maxCodePoints)).strip();
        }
        return s;
    }
}
