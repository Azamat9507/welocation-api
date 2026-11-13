package com.minsu.welocation.spot;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// What a LEISURE spot is for. TourAPI's own codes set most of them (TourApiMapper); PAINTBALL is never set by the
// sync, because TourAPI files paintball, BB guns and laser tag together as 서바이벌 (SURVIVAL), and only an admin
// who knows the place can say it uses paint.
// The words let a search in any of the site's languages find the type: Korean names say 서바이벌, a visitor
// types "paintball".
public enum Activity {
    KART("카트", "카팅", "kart", "karting", "go-kart", "gokart", "картинг", "карт"),
    SHOOTING("사격", "사격장", "shooting", "shooting range", "o'q otish", "стрельба", "тир"),
    ARCHERY("국궁", "양궁", "활쏘기", "archery", "kamon", "kamondan o'q otish", "стрельба из лука", "лук"),
    PAINTBALL("페인트볼", "paintball", "peyntbol", "пейнтбол"),
    SURVIVAL("서바이벌", "survival", "airsoft", "laser tag", "лазертаг", "страйкбол"),
    ATV("atv", "사륜", "квадроцикл"),
    OFF_ROAD("오프로드", "off-road", "offroad", "внедорожник"),
    BUNGEE("번지", "bungee", "банджи"),
    JET_SKI("제트스키", "윈드서핑", "jet ski", "jetski", "windsurf", "гидроцикл", "виндсерфинг"),
    KAYAK("카약", "카누", "kayak", "canoe", "байдарка", "каяк", "каноэ"),
    RAFTING("래프팅", "rafting", "рафтинг"),
    SKYDIVING("스카이다이빙", "skydiving", "скайдайвинг", "парашют"),
    PARAGLIDING("패러글라이딩", "행글라이딩", "paragliding", "hang gliding", "paraplan", "параплан"),
    BALLOON("열기구", "hot air balloon", "balloon", "воздушный шар"),
    THEME_PARK("테마파크", "놀이공원", "theme park", "amusement park", "attraksion", "парк развлечений", "аттракцион");

    private final List<String> words;

    Activity(String... words) {
        this.words = Arrays.stream(words).map(Activity::normalize).toList();
    }

    // Activities a typed text asks for: "paint ball", "Paintball" and "пейнтбол" all give PAINTBALL. Paintball
    // also brings SURVIVAL, since that is where TourAPI's paintball places are, if any.
    public static Set<Activity> named(String text) {
        if (text == null) {
            return Set.of();
        }
        String typed = normalize(text);
        if (typed.length() < 2) {
            return Set.of();
        }
        for (Activity activity : values()) {
            if (activity.words.stream().anyMatch(word -> word.equals(typed))) {
                return activity == PAINTBALL ? Set.of(PAINTBALL, SURVIVAL) : Set.of(activity);
            }
        }
        return Set.of();
    }

    private static String normalize(String s) {
        return s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_]+", "");
    }
}
