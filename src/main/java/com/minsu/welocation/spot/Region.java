package com.minsu.welocation.spot;

// the 17 first-level administrative divisions of Korea
public enum Region {
    SEOUL,
    BUSAN,
    DAEGU,
    INCHEON,
    GWANGJU,
    DAEJEON,
    ULSAN,
    SEJONG,
    GYEONGGI,
    GANGWON,
    CHUNGBUK,
    CHUNGNAM,
    JEONBUK,
    JEONNAM,
    GYEONGBUK,
    GYEONGNAM,
    JEJU;

    // Province of a Korean address from its first word: 서울특별시, 경기도, 강원특별자치도 and the short forms
    // (서울, 경기, 충북) all work. Empty for anything else, so the caller asks instead of guessing.
    public static java.util.Optional<Region> fromAddress(String address) {
        if (address == null || address.isBlank()) {
            return java.util.Optional.empty();
        }
        String first = address.strip().split("\\s+")[0];
        String[][] prefixes = {
                {"서울", "SEOUL"}, {"부산", "BUSAN"}, {"대구", "DAEGU"}, {"인천", "INCHEON"}, {"광주", "GWANGJU"},
                {"대전", "DAEJEON"}, {"울산", "ULSAN"}, {"세종", "SEJONG"}, {"경기", "GYEONGGI"}, {"강원", "GANGWON"},
                {"충북", "CHUNGBUK"}, {"충청북", "CHUNGBUK"}, {"충남", "CHUNGNAM"}, {"충청남", "CHUNGNAM"},
                {"전북", "JEONBUK"}, {"전라북", "JEONBUK"}, {"전남", "JEONNAM"}, {"전라남", "JEONNAM"},
                {"경북", "GYEONGBUK"}, {"경상북", "GYEONGBUK"}, {"경남", "GYEONGNAM"}, {"경상남", "GYEONGNAM"},
                {"제주", "JEJU"}};
        for (String[] p : prefixes) {
            if (first.startsWith(p[0])) {
                return java.util.Optional.of(valueOf(p[1]));
            }
        }
        return java.util.Optional.empty();
    }
}
