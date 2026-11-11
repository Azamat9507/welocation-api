package com.minsu.welocation.spot;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class SpotDtos {

    private SpotDtos() {
    }

    public record SpotSearch(
            Region region,
            SpotCategory category,
            SpotTag tag,
            @Min(0) Integer page,
            @Min(1) @Max(50) Integer size) {

        int pageOrDefault() {
            return page == null ? 0 : page;
        }

        int sizeOrDefault() {
            return size == null ? 20 : size;
        }
    }

    public record SpotSummary(Long id, String name, SpotCategory category, Region region,
                              String address, double latitude, double longitude, String thumbnailUrl) {

        static SpotSummary from(Spot spot) {
            return new SpotSummary(spot.getId(), spot.getName(), spot.getCategory(), spot.getRegion(),
                    spot.getAddress(), spot.getLatitude(), spot.getLongitude(), spot.getThumbnailUrl());
        }
    }

    public record SpotDetail(Long id, String name, SpotCategory category, Region region, String address,
                             double latitude, double longitude, String description,
                             List<SpotTag> tags, List<String> images) {

        static SpotDetail from(Spot spot) {
            return new SpotDetail(spot.getId(), spot.getName(), spot.getCategory(), spot.getRegion(),
                    spot.getAddress(), spot.getLatitude(), spot.getLongitude(), spot.getDescription(),
                    spot.getTags().stream().sorted().toList(),
                    spot.getImages().stream().map(SpotImage::getUrl).toList());
        }
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
}
