package com.minsu.welocation.spot;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class SpotDtos {

    private SpotDtos() {
    }

    public record SpotSearch(
            Region region,
            SpotCategory category,
            Activity activity,
            SpotTag tag,
            @Size(max = 50) String q,
            @Min(0) @Max(10_000) Integer page,
            @Min(1) @Max(50) Integer size) {

        public SpotSearch {
            q = q == null || q.isBlank() ? null : q.strip();
        }

        int pageOrDefault() {
            return page == null ? 0 : page;
        }

        int sizeOrDefault() {
            return size == null ? 20 : size;
        }
    }

    public record SpotSummary(Long id, String name, SpotCategory category, Activity activity, Region region,
                              String address, double latitude, double longitude, String thumbnailUrl,
                              List<SpotTag> tags, int likeCount, long reviewCount, Double averageRating,
                              List<String> photos) {

        // photos: the first few gallery images (thumbnail first) for cards that flip through them
        static SpotSummary from(Spot spot, Rating rating, List<String> photos) {
            return new SpotSummary(spot.getId(), spot.getName(), spot.getCategory(), spot.getActivity(), spot.getRegion(),
                    spot.getAddress(), spot.getLatitude(), spot.getLongitude(), spot.getThumbnailUrl(),
                    spot.getTags().stream().sorted().toList(), spot.getLikeCount(), rating.count(), rating.average(),
                    photos);
        }
    }

    // Every spot as a dot for the home page map: coordinates rounded to 3 decimals (about 100 m), grouped by
    // category so the page can draw one path per colour, plus the number of spots in each region.
    public record SpotMap(int total, Map<SpotCategory, List<double[]>> points, Map<Region, Long> regions) {
    }

    // average is null while a spot has no reviews, so "no rating yet" can't be mistaken for 0 stars
    public record Rating(long count, Double average) {

        static final Rating NONE = new Rating(0, null);

        static Rating of(long count, double average) {
            return new Rating(count, Math.round(average * 10) / 10.0);
        }
    }

    // manual: added by an admin, not from TourAPI; its photos are ours, not 공공누리, and only such spots can be edited
    public record SpotDetail(Long id, String name, SpotCategory category, Activity activity, Region region,
                             String address, double latitude, double longitude, String description,
                             List<SpotTag> tags, List<String> images, int likeCount, long reviewCount, Double averageRating,
                             String shortsVideoId, boolean manual, List<SceneView> scenes) {

        static SpotDetail from(Spot spot, Rating rating, List<SceneView> scenes) {
            return new SpotDetail(spot.getId(), spot.getName(), spot.getCategory(), spot.getActivity(), spot.getRegion(),
                    spot.getAddress(), spot.getLatitude(), spot.getLongitude(), spot.getDescription(),
                    spot.getTags().stream().sorted().toList(),
                    spot.getImages().stream().map(SpotImage::getUrl).toList(),
                    spot.getLikeCount(), rating.count(), rating.average(), spot.getShortsVideoId(),
                    spot.getSource() == SpotSource.MANUAL, scenes);
        }
    }

    // a spot an admin adds or edits by hand; region is worked out from the address when left out
    public record ManualSpotRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull SpotCategory category,
            Activity activity,
            Region region,
            @NotBlank @Size(max = 300) String address,
            @NotNull @DecimalMin("33") @DecimalMax("39") Double latitude,
            @NotNull @DecimalMin("124") @DecimalMax("132.5") Double longitude,
            @Size(max = 2000) String description) {
    }

    // one place the geocoder found for an address
    public record GeocodeHit(double latitude, double longitude, String label) {
    }

    // one card of the /shorts feed
    public record ShortsItem(Long id, String name, SpotCategory category, Region region, String address,
                             String thumbnailUrl, String videoId) {

        static ShortsItem from(Spot spot) {
            return new ShortsItem(spot.getId(), spot.getName(), spot.getCategory(), spot.getRegion(),
                    spot.getAddress(), spot.getThumbnailUrl(), spot.getShortsVideoId());
        }
    }

    public record ShortsRequest(@NotBlank @Size(max = 200) String url) {
    }

    public record NearbySearch(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            @NotNull @DecimalMin("0.1") @DecimalMax("50") Double radiusKm,
            SpotCategory category,
            @Min(1) @Max(50) Integer size) {

        int sizeOrDefault() {
            return size == null ? 20 : size;
        }
    }

    public record NearbySpot(Long id, String name, SpotCategory category, Region region, String address,
                             double latitude, double longitude, String thumbnailUrl, int distanceMeters) {

        static NearbySpot from(SpotRepository.NearbyRow row) {
            return new NearbySpot(row.getId(), row.getName(), row.getCategory(), row.getRegion(),
                    row.getAddress(), row.getLatitude(), row.getLongitude(), row.getThumbnailUrl(),
                    (int) Math.round(row.getDistanceMeters()));
        }
    }

    // a drama or film shot at the spot; scene is what happens there, in Korean
    public record SceneView(String work, SceneKind kind, String scene) {

        static SceneView from(SpotScene scene) {
            return new SceneView(scene.getWork(), scene.getKind(), scene.getScene());
        }
    }

    // cover: a photo from one of its spots, null when none of them has one
    public record Work(String title, SceneKind kind, long spotCount, String coverUrl) {
    }

    public record WorkSpot(SpotSummary spot, String scene) {
    }

    public record WorkSpots(Work work, List<WorkSpot> spots) {
    }
}
