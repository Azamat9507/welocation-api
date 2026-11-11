package com.minsu.welocation.guide;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GuideProperties.class)
class GuideConfig {

    // Replaces Spring AI's own client bean for two reasons:
    // - Spring AI refuses to start without a key, and the rest of the API shouldn't depend on the guide being set up.
    //   Without a key the client is built but never called; GuideService answers 503 first.
    // - the timeout: the SDK has no Spring property for it, and a slow model must not hold a request for minutes.
    //   No SDK retries either; a failed call goes to the fallback model instead.
    @Bean
    Client googleGenAiClient(GuideProperties properties) {
        return Client.builder()
                .apiKey(properties.configured() ? properties.apiKey() : "not-configured")
                .httpOptions(HttpOptions.builder()
                        .timeout((int) properties.timeout().toMillis())
                        .retryOptions(HttpRetryOptions.builder().attempts(1))
                        .build())
                .build();
    }
}
