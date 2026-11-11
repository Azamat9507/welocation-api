package com.minsu.welocation.tourapi;

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
        // detailImage2 costs one request per spot; with the list sync (~80) this stays under 1,000 a day
        @Min(0) @Max(900) int galleryPerNight,
        @Min(1) @Max(20) int galleryMaxImages) {
}
