package com.minsu.welocation.tourapi;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("welocation.tourapi")
public record TourApiProperties(
        @NotBlank String baseUrl,
        String key,
        @Min(1) @Max(1000) int pageSize,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(5) int maxAttempts,
        @NotNull Duration retryBackoff,
        // detailImage2 costs exactly one request per spot (no retries). The list sync usually needs ~80 and
        // the stays add a few dozen more, so 600 still leaves room on a normal night of the 1,000 a day.
        @Min(0) @Max(600) int galleryPerNight,
        @Min(1) @Max(20) int galleryMaxImages,
        // a stay is kept only this close to a stored beach, lake or river
        @DecimalMin("0.1") @DecimalMax("5") double stayWatersideKm) {
}
