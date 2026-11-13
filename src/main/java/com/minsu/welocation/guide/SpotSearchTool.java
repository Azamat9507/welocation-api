package com.minsu.welocation.guide;

import com.minsu.welocation.spot.Activity;
import com.minsu.welocation.spot.Region;
import com.minsu.welocation.spot.SpotCategory;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotRepository.Located;
import com.minsu.welocation.spot.SpotService;
import com.minsu.welocation.spot.SpotTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// One instance per question, shared by the fallback model. It remembers every spot it showed the model, so the answer can only pick from those.
// Read-only by design: the model gets no tool that writes anything or touches user data.
@Slf4j
class SpotSearchTool {

    static final int LIMIT = 15;
    static final int MAX_NAME_LENGTH = 30;

    private final SpotRepository spotRepository;
    private final SpotService spotService;
    private final int maxSearches;
    private final Set<Long> shown = new LinkedHashSet<>();
    private int searches;

    SpotSearchTool(SpotRepository spotRepository, SpotService spotService, int maxSearches) {
        this.spotRepository = spotRepository;
        this.spotService = spotService;
        this.maxSearches = maxSearches;
    }

    @Tool(description = """
            Search WeLocation's database of places in Korea. Returns at most 15 spots, most liked first, or nearest \
            first when nearCategory is given. Spots have no descriptions or opening hours; what you see here is all \
            that is known about them.""")
    public List<Found> searchSpots(
            @ToolParam(required = false, description = "province or metropolitan city") Region region,
            @ToolParam(required = false, description = "kind of place") SpotCategory category,
            @ToolParam(required = false, description = "what you do there, for LEISURE spots: KART for karting, "
                    + "ARCHERY for bows, PAINTBALL for paintball (also try SURVIVAL, the Korean 서바이벌 game, "
                    + "which can be paintball, BB guns or laser tag)") Activity activity,
            @ToolParam(required = false, description = "text the spot's Korean name must contain, e.g. 오션 or 바다")
            String nameContains,
            @ToolParam(required = false, description = "keep only spots close to a spot of this kind, "
                    + "e.g. BEACH for a café by the sea") SpotCategory nearCategory,
            @ToolParam(required = false, description = "how close for nearCategory, in km, 0.1 to 3, default 1")
            Double withinKm) {
        // Gemini can ask for many searches in one turn. Whatever is thrown here goes back to the model as the
        // tool's answer, so the messages are ours and short: a database error must not end up quoted to the user.
        if (++searches > maxSearches) {
            throw new IllegalStateException("search limit reached; answer with the spots already found");
        }
        try {
            return search(region, category, activity, nameContains, nearCategory, withinKm);
        } catch (RuntimeException e) {
            log.warn("guide search failed: {}", e.getClass().getSimpleName());
            throw new IllegalStateException("search failed; answer with the spots already found");
        }
    }

    private List<Found> search(Region region, SpotCategory category, Activity activity, String nameContains,
                               SpotCategory nearCategory, Double withinKm) {
        String regionName = region == null ? null : region.name();
        String categoryName = category == null ? null : category.name();
        String activityName = activity == null ? null : activity.name();
        String nameLike = likePattern(nameContains);
        Map<Long, Double> nearMeters = new LinkedHashMap<>();
        if (nearCategory == null) {
            spotRepository.findCandidates(regionName, categoryName, activityName, nameLike, LIMIT)
                    .forEach(candidate -> nearMeters.put(candidate.getId(), null));
        } else {
            double meters = 1000 * (withinKm == null ? 1 : Math.clamp(withinKm, 0.1, 3));
            nearest(spotRepository.findLocated(regionName, categoryName, activityName, nameLike),
                    spotRepository.findLocated(null, nearCategory.name(), null, null), meters)
                    .forEach(nearMeters::put);
        }
        Map<Long, SpotSummary> spots = spotService.summariesById(List.copyOf(nearMeters.keySet()));
        List<Found> found = new ArrayList<>();
        nearMeters.forEach((id, meters) -> {
            SpotSummary spot = spots.get(id);
            if (spot != null) {
                shown.add(id);
                found.add(Found.of(spot, meters));
            }
        });
        return found;
    }

    // For each spot, the distance to the closest landmark (another spot) within `meters`; nearest first, ties by id,
    // at most LIMIT. Thousands of spots times a few hundred landmarks is about a million comparisons, so the
    // coordinates are copied out of the query's proxies into plain arrays first.
    static Map<Long, Double> nearest(List<Located> spots, List<Located> landmarks, double meters) {
        int n = landmarks.size();
        long[] ids = new long[n];
        double[] lats = new double[n];
        double[] lngs = new double[n];
        for (int i = 0; i < n; i++) {
            Located landmark = landmarks.get(i);
            ids[i] = landmark.getId();
            lats[i] = landmark.getLatitude();
            lngs[i] = landmark.getLongitude();
        }
        // a degree of latitude is about 111 km, so this skips most pairs before the trigonometry
        double latWindow = meters / 111_000;
        record Hit(long id, double meters) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Located spot : spots) {
            long id = spot.getId();
            double lat = spot.getLatitude();
            double lng = spot.getLongitude();
            double best = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (ids[i] != id && Math.abs(lats[i] - lat) <= latWindow) {
                    best = Math.min(best, Distances.meters(lat, lng, lats[i], lngs[i]));
                }
            }
            if (best <= meters) {
                hits.add(new Hit(id, best));
            }
        }
        Map<Long, Double> nearest = new LinkedHashMap<>();
        hits.stream()
                .sorted(Comparator.comparingDouble(Hit::meters).thenComparingLong(Hit::id))
                .limit(LIMIT)
                .forEach(hit -> nearest.put(hit.id(), hit.meters()));
        return nearest;
    }

    boolean exhausted() {
        return searches >= maxSearches;
    }

    Set<Long> shown() {
        return Collections.unmodifiableSet(shown);
    }

    // the model writes this text; it must not be able to widen the match with its own % or _
    static String likePattern(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_NAME_LENGTH);
        }
        return "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    // what the model sees: no address or coordinates, so it has nothing to quote that the page doesn't show itself
    record Found(long id, String name, SpotCategory category, Activity activity, Region region, List<SpotTag> tags,
                 int likeCount, long reviewCount, Double averageRating, boolean hasPhoto, Integer metersToNearCategory) {

        static Found of(SpotSummary spot, Double nearMeters) {
            return new Found(spot.id(), spot.name(), spot.category(), spot.activity(), spot.region(), spot.tags(),
                    spot.likeCount(),
                    spot.reviewCount(), spot.averageRating(), spot.thumbnailUrl() != null,
                    nearMeters == null ? null : (int) Math.round(nearMeters));
        }
    }
}
