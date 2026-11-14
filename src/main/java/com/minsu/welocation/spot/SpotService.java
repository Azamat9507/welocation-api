package com.minsu.welocation.spot;

import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.review.ReviewRepository;
import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import com.minsu.welocation.spot.SpotDtos.NearbySpot;
import com.minsu.welocation.spot.SpotDtos.Rating;
import com.minsu.welocation.spot.SpotDtos.SceneView;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotMap;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import com.minsu.welocation.spot.SpotDtos.Work;
import com.minsu.welocation.spot.SpotDtos.WorkSpot;
import com.minsu.welocation.spot.SpotDtos.WorkSpots;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final SpotSceneRepository sceneRepository;

    public PageResponse<SpotSummary> search(SpotSearch search) {
        // spots with a photo first, newest first within each group
        PageRequest pageable = PageRequest.of(search.pageOrDefault(), search.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "hasImage", "id"));
        return summaries(spotRepository.findAll(SpotSpecs.matching(search.region(), search.category(), search.activity(),
                search.tag(), search.q(), Boolean.FALSE.equals(search.scenes())), pageable));
    }

    // tags come in one batched query (Spot.tags), ratings in one grouped query and card photos in one more,
    // for the whole page
    public PageResponse<SpotSummary> summaries(Page<Spot> page) {
        List<Long> ids = page.map(Spot::getId).getContent();
        Map<Long, Rating> ratings = ratingsOf(ids);
        Map<Long, List<String>> photos = photosOf(ids);
        return PageResponse.from(page.map(spot -> summary(spot, ratings, photos)));
    }

    // for lists ordered somewhere else (the weekly ranking): the caller keeps its order and skips missing ids
    public Map<Long, SpotSummary> summariesById(List<Long> ids) {
        Map<Long, Rating> ratings = ratingsOf(ids);
        Map<Long, List<String>> photos = photosOf(ids);
        return spotRepository.findAllById(ids).stream().collect(Collectors.toMap(
                Spot::getId, spot -> summary(spot, ratings, photos)));
    }

    private static SpotSummary summary(Spot spot, Map<Long, Rating> ratings, Map<Long, List<String>> photos) {
        return SpotSummary.from(spot, ratings.getOrDefault(spot.getId(), Rating.NONE),
                photos.getOrDefault(spot.getId(), List.of()));
    }

    // A card flips through at most this many photos; more would only add bytes to every list page
    static final int CARD_PHOTOS = 4;

    private Map<Long, List<String>> photosOf(List<Long> spotIds) {
        if (spotIds.isEmpty()) {
            return Map.of();
        }
        return spotRepository.findFirstPhotos(spotIds, CARD_PHOTOS).stream().collect(Collectors.groupingBy(
                SpotRepository.Photo::getSpotId, Collectors.mapping(SpotRepository.Photo::getUrl, Collectors.toList())));
    }

    public SpotMap map() {
        Map<SpotCategory, List<double[]>> points = new EnumMap<>(SpotCategory.class);
        Map<Region, Long> regions = new EnumMap<>(Region.class);
        List<SpotRepository.MapPoint> rows = spotRepository.findMapPoints();
        for (SpotRepository.MapPoint row : rows) {
            points.computeIfAbsent(SpotCategory.valueOf(row.getCategory()), c -> new ArrayList<>())
                    .add(new double[] {round3(row.getLatitude()), round3(row.getLongitude())});
            regions.merge(Region.valueOf(row.getRegion()), 1L, Long::sum);
        }
        return new SpotMap(rows.size(), points, regions);
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    public SpotDetail get(Long id) {
        Spot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("spot " + id + " not found"));
        List<SceneView> scenes = sceneRepository.findBySpotIdOrderByIdAsc(id).stream().map(SceneView::from).toList();
        return SpotDetail.from(spot, ratingsOf(List.of(id)).getOrDefault(id, Rating.NONE), scenes);
    }

    public List<Work> works() {
        return sceneRepository.findWorks().stream()
                .map(row -> new Work(row.getWork(), row.getKind(), row.getSpots(), row.getCover()))
                .toList();
    }

    // the work's spots in the order the source lists them, photo first so the page opens on pictures
    public WorkSpots work(String title) {
        List<SpotScene> scenes = sceneRepository.findByWorkOrderByIdAsc(title);
        if (scenes.isEmpty()) {
            throw new NotFoundException("no spots for this work");
        }
        Map<Long, SpotSummary> spots = summariesById(scenes.stream().map(scene -> scene.getSpot().getId()).toList());
        List<WorkSpot> found = scenes.stream()
                .filter(scene -> spots.containsKey(scene.getSpot().getId()))
                .map(scene -> new WorkSpot(spots.get(scene.getSpot().getId()), scene.getScene()))
                .sorted(Comparator.comparing((WorkSpot spot) -> spot.spot().thumbnailUrl() == null))
                .toList();
        SpotScene first = scenes.getFirst();
        // the same pick as findWorks (MAX), so the card and the page show the same cover
        String cover = found.stream().map(spot -> spot.spot().thumbnailUrl()).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new WorkSpots(new Work(title, first.getKind(), found.size(), cover), found);
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
    public static String boundingBox(double lat, double lng, double radiusKm) {
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
