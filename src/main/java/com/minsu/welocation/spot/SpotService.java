package com.minsu.welocation.spot;

import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.review.ReviewRepository;
import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import com.minsu.welocation.spot.SpotDtos.NearbySpot;
import com.minsu.welocation.spot.SpotDtos.Rating;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpotService {

    // same sphere ST_Distance_Sphere uses, so the box is measured in the same units as the distance check
    private static final double EARTH_RADIUS_KM = 6370.986;
    private static final double KM_PER_DEGREE = Math.PI * EARTH_RADIUS_KM / 180;
    private static final double[] SEARCH_STEPS_KM = {1, 3, 10, 30};

    private final SpotRepository spotRepository;
    private final ReviewRepository reviewRepository;

    public PageResponse<SpotSummary> search(SpotSearch search) {
        PageRequest pageable = PageRequest.of(search.pageOrDefault(), search.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "id"));
        return summaries(spotRepository.findAll(SpotSpecs.matching(search.region(), search.category(), search.tag()), pageable));
    }

    // tags come in one batched query (Spot.tags) and ratings in one grouped query for the whole page
    public PageResponse<SpotSummary> summaries(Page<Spot> page) {
        Map<Long, Rating> ratings = ratingsOf(page.map(Spot::getId).getContent());
        return PageResponse.from(page.map(spot -> SpotSummary.from(spot, ratings.getOrDefault(spot.getId(), Rating.NONE))));
    }

    public SpotDetail get(Long id) {
        Spot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("spot " + id + " not found"));
        return SpotDetail.from(spot, ratingsOf(List.of(id)).getOrDefault(id, Rating.NONE));
    }

    private Map<Long, Rating> ratingsOf(List<Long> spotIds) {
        if (spotIds.isEmpty()) {
            return Map.of();
        }
        return reviewRepository.summarize(spotIds).stream().collect(Collectors.toMap(
                ReviewRepository.RatingSummary::getSpotId,
                r -> Rating.of(r.getReviewCount(), r.getAverageRating())));
    }

    // Every spot in the bounding box gets its distance computed and sorted, so a wide radius over a dense
    // city is slow. Searching small circles first is cheaper and gives the same answer: once a circle
    // holds `size` spots, everything outside it is farther away than all of them.
    // A circle that comes back short is also a density sample. If even the full radius isn't expected to
    // fill the page (k * (R/r)^2 < size), skip the remaining steps, or a rare category pays for every step.
    public List<NearbySpot> nearby(NearbySearch search) {
        String category = search.category() == null ? null : search.category().name();
        int size = search.sizeOrDefault();
        double radiusKm = search.radiusKm();
        for (double step : SEARCH_STEPS_KM) {
            if (step >= radiusKm) {
                break;
            }
            List<SpotRepository.NearbyRow> rows = findWithin(search, step, category, size);
            if (rows.size() == size) {
                return toSpots(rows);
            }
            if (rows.size() * Math.pow(radiusKm / step, 2) < size) {
                break;
            }
        }
        return toSpots(findWithin(search, radiusKm, category, size));
    }

    private List<SpotRepository.NearbyRow> findWithin(NearbySearch search, double radiusKm, String category, int size) {
        return spotRepository.findNearby(search.lat(), search.lng(), radiusKm * 1000,
                boundingBox(search.lat(), search.lng(), radiusKm), category, size);
    }

    private static List<NearbySpot> toSpots(List<SpotRepository.NearbyRow> rows) {
        return rows.stream().map(NearbySpot::from).toList();
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
