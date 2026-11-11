package com.minsu.welocation;

import org.springframework.boot.SpringApplication;
import com.minsu.welocation.tourapi.TourApiProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(TourApiProperties.class)
public class WeLocationApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeLocationApplication.class, args);
    }

    // injected wherever time decides an outcome (token expiry, rate limit windows) so tests can move it
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
