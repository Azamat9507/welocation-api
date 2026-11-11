package com.minsu.welocation.guide;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("welocation.guide")
public record GuideProperties(
        String apiKey,
        String model,
        String fallbackModel,
        Duration timeout,
        Duration deadline,
        int maxOutputTokens,
        Integer thinkingBudget,
        int maxSearches,
        int perUserDaily,
        int globalDaily) {

    boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
