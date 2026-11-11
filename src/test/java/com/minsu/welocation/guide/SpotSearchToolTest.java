package com.minsu.welocation.guide;

import com.minsu.welocation.spot.SpotRepository.Located;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotSearchToolTest {

    record At(Long getId, double getLatitude, double getLongitude) implements Located {
    }

    @Test
    void aSpotIsNeverItsOwnLandmark() {
        // "beaches near a beach": a lone beach is 0 m from itself and must not count
        At lone = new At(1L, 35.0, 129.0);
        At pairA = new At(2L, 36.0, 129.0);
        At pairB = new At(3L, 36.002, 129.0);
        List<At> beaches = List.of(lone, pairA, pairB);

        assertThat(SpotSearchTool.nearest(List.copyOf(beaches), List.copyOf(beaches), 1000))
                .containsOnlyKeys(2L, 3L)
                .allSatisfy((id, meters) -> assertThat(meters).isBetween(220.0, 225.0));
    }

    @Test
    void nearestFirstThenByIdAndAtMostFifteen() {
        At landmark = new At(100L, 35.0, 129.0);
        List<Located> spots = new ArrayList<>();
        for (long id = 1; id <= 20; id++) {
            // ids 1 and 2 at the same distance; the rest a little farther each
            double offset = id <= 2 ? 0.001 : 0.001 * id;
            spots.add(new At(id, 35.0 + offset, 129.0));
        }

        assertThat(SpotSearchTool.nearest(spots, List.of(landmark), 3000).keySet())
                .hasSize(SpotSearchTool.LIMIT)
                .startsWith(1L, 2L, 3L);
    }
}
