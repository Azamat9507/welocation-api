package com.minsu.welocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
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
