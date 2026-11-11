package com.minsu.welocation.spot;

import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import com.minsu.welocation.spot.SpotDtos.NearbySpot;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpotService {

    // same sphere ST_Distance_Sphere uses, so the box is measured in the same units as the distance check
    private static final double EARTH_RADIUS_KM = 6370.986;
    private static final double KM_PER_DEGREE = Math.PI * EARTH_RADIUS_KM / 180;

    private final SpotRepository spotRepository;

    public PageResponse<SpotSummary> search(SpotSearch search) {
        PageRequest pageable = PageRequest.of(search.pageOrDefault(), search.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(spotRepository
                .findAll(SpotSpecs.matching(search.region(), search.category(), search.tag()), pageable)
                .map(SpotSummary::from));
    }

    public SpotDetail get(Long id) {
        Spot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("spot " + id + " not found"));
        return SpotDetail.from(spot);
    }

    public List<NearbySpot> nearby(NearbySearch search) {
        String box = boundingBox(search.lat(), search.lng(), search.radiusKm());
        String category = search.category() == null ? null : search.category().name();
        return spotRepository.findNearby(search.lat(), search.lng(), search.radiusKm() * 1000, box, category,
                        search.sizeOrDefault())
                .stream()
                .map(NearbySpot::from)
                .toList();
    }

    // The box only has to contain the whole circle; anything extra is dropped by the distance check.
    // Longitude degrees shrink with latitude, so the edge farthest from the equator sets the width.
    // Near the poles or ±180° the box gets clipped and can miss spots; WeLocation only covers Korea.
    static String boundingBox(double lat, double lng, double radiusKm) {
        double padded = radiusKm * 1.01; // slack so rounding the WKT to 6 decimals never trims the edge
        double dLat = padded / KM_PER_DEGREE;
        double minLat = Math.max(-90, lat - dLat);
        double maxLat = Math.min(90, lat + dLat);
        double cos = Math.cos(Math.toRadians(Math.max(Math.abs(minLat), Math.abs(maxLat))));
        double dLng = cos < 1e-6 ? 360 : padded / (KM_PER_DEGREE * cos);
        double minLng = Math.max(-180, lng - dLng);
        double maxLng = Math.min(180, lng + dLng);
        return String.format(Locale.ROOT, "POLYGON((%.6f %.6f, %.6f %.6f, %.6f %.6f, %.6f %.6f, %.6f %.6f))",
                minLng, minLat, maxLng, minLat, maxLng, maxLat, minLng, maxLat, minLng, minLat);
    }
}
