package com.minsu.welocation.spot;

import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Which circles get queried only shows up in latency, so it is pinned here with a mocked repository.
class SpotServiceNearbyTest {

    SpotRepository repository = mock(SpotRepository.class);
    SpotService service = new SpotService(repository);

    private void radius(double km, List<SpotRepository.NearbyRow> rows) {
        when(repository.findNearby(anyDouble(), anyDouble(), eq(km * 1000), any(), any(), anyInt())).thenReturn(rows);
    }

    private static List<SpotRepository.NearbyRow> rows(int count) {
        return Collections.nCopies(count, mock(SpotRepository.NearbyRow.class));
    }

    private void verifyQueried(double... km) {
        var order = inOrder(repository);
        for (double radius : km) {
            order.verify(repository).findNearby(anyDouble(), anyDouble(), eq(radius * 1000), any(), any(), anyInt());
        }
        verify(repository, times(km.length)).findNearby(anyDouble(), anyDouble(), anyDouble(), any(), any(), anyInt());
    }

    @Test
    void stopsAtTheFirstCircleThatFillsThePage() {
        radius(1, rows(2));
        radius(3, rows(20));

        service.nearby(new NearbySearch(37.5, 127.0, 50.0, null, 20));

        verifyQueried(1, 3);
    }

    // nothing within 1 km of a 50 km search predicts nothing to fill the page: go straight to 50 km
    @Test
    void skipsToTheFullRadiusWhenASmallCircleComesBackEmpty() {
        radius(1, rows(0));
        radius(50, rows(0));

        service.nearby(new NearbySearch(37.5, 127.0, 50.0, SpotCategory.BEACH, 20));

        verifyQueried(1, 50);
    }

    @Test
    void neverSearchesBeyondTheRequestedRadius() {
        radius(1, rows(5));
        radius(3, rows(5));

        service.nearby(new NearbySearch(37.5, 127.0, 3.0, null, 20));

        verifyQueried(1, 3);
        verify(repository, never()).findNearby(anyDouble(), anyDouble(), eq(10_000.0), any(), any(), anyInt());
    }
}
