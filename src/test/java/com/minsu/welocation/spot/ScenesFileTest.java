package com.minsu.welocation.spot;

import com.minsu.welocation.spot.SceneImporter.Place;
import com.minsu.welocation.spot.SceneImporter.SceneRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// The real scenes.json, checked against what the tables and the importer accept, so a bad edit fails here
// and not as a skipped place in the production log.
class ScenesFileTest {

    static Place[] places() throws Exception {
        try (InputStream in = ScenesFileTest.class.getResourceAsStream("/scenes/scenes.json")) {
            return JsonMapper.builder().build().readValue(in, Place[].class);
        }
    }

    @Test
    void everyPlaceFitsTheTables() throws Exception {
        Place[] places = places();
        assertThat(places).hasSizeGreaterThan(200);
        Set<String> keys = new HashSet<>();
        for (Place p : places) {
            assertThat(keys.add(p.source() + " " + p.externalId())).as("duplicate %s", p.externalId()).isTrue();
            assertThat(p.source()).isIn(SpotSource.TOURAPI, SpotSource.SCENE);
            assertThat(p.externalId()).isNotBlank().hasSizeLessThanOrEqualTo(50);
            assertThat(p.name()).isNotBlank().hasSizeLessThanOrEqualTo(200);
            assertThat(p.address()).isNotBlank().hasSizeLessThanOrEqualTo(300);
            assertThat(p.region()).as(p.name()).isNotNull();
            assertThat(p.latitude()).isBetween(33.0, 39.0);
            assertThat(p.longitude()).isBetween(124.0, 132.0);
            if (p.image() != null) {
                assertThat(p.image()).startsWith("https://tong.visitkorea.or.kr/");
            }
            assertThat(p.scenes()).isNotEmpty();
            Set<String> works = new HashSet<>();
            for (SceneRow s : p.scenes()) {
                assertThat(works.add(s.work())).as("%s twice at %s", s.work(), p.name()).isTrue();
                assertThat(s.work()).isNotBlank().hasSizeLessThanOrEqualTo(SpotScene.MAX_WORK_LENGTH);
                assertThat(s.kind()).isNotNull();
                if (s.scene() != null) {
                    assertThat(s.scene()).hasSizeLessThanOrEqualTo(SpotScene.MAX_SCENE_LENGTH);
                }
            }
        }
    }

    // /works groups by title and kind, so one title with two kinds would show up as two works
    @Test
    void aTitleHasOneKind() throws Exception {
        Map<String, Set<SceneKind>> kinds = new HashMap<>();
        Stream.of(places()).flatMap(p -> p.scenes().stream())
                .forEach(s -> kinds.computeIfAbsent(s.work(), w -> new HashSet<>()).add(s.kind()));
        assertThat(kinds).allSatisfy((work, k) -> assertThat(k).as(work).hasSize(1));
    }

    @Test
    void hasTheClassicsThatWereAskedFor() throws Exception {
        List<String> works = Stream.of(places()).flatMap(p -> p.scenes().stream()).map(SceneRow::work).toList();
        assertThat(works).contains("겨울연가", "올인", "아이리스", "대장금", "주몽", "해신", "오징어게임");
    }
}
