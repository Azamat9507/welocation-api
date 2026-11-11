package com.minsu.welocation.auth;

import com.minsu.welocation.common.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    static class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-11T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsUpToTheLimitThenAgainInTheNextWindow() {
        MovableClock clock = new MovableClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            limiter.check("k", 3);
        }
        assertThatThrownBy(() -> limiter.check("k", 3))
                .isInstanceOfSatisfying(TooManyRequestsException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));

        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.check("k", 3)).doesNotThrowAnyException();
    }

    // a flood of fresh keys must hit a ceiling instead of growing the map and scanning it on every call
    @Test
    void refusesNewKeysWhenFullUntilOldWindowsExpire() {
        MovableClock clock = new MovableClock();
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
            limiter.check("spray-" + i, 5);
        }

        assertThatThrownBy(() -> limiter.check("newcomer", 5)).isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.check("spray-1", 5)).doesNotThrowAnyException();

        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.check("newcomer", 5)).doesNotThrowAnyException();
    }

    @Test
    void ipv6AddressesInOneSlash64ShareABudget() {
        assertThat(RateLimiter.clientKey("2001:db8:1:2:aaaa::1"))
                .isEqualTo(RateLimiter.clientKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isNotEqualTo(RateLimiter.clientKey("2001:db8:1:3::1"));
        assertThat(RateLimiter.clientKey("203.0.113.7")).isNotEqualTo(RateLimiter.clientKey("203.0.113.8"));
    }
}
