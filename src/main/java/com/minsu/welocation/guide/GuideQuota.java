package com.minsu.welocation.guide;

import com.minsu.welocation.common.ServiceUnavailableException;
import com.minsu.welocation.common.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

// Daily limits per user and for everyone together, counted in Redis so they hold across restarts and instances.
// A day is a calendar day in Korea. If Redis can't count, the guide stays closed: an uncounted model call is
// money nobody agreed to spend.
@Component
@RequiredArgsConstructor
class GuideQuota {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final String KEY_PREFIX = "guide:quota:";
    static final Duration TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final GuideProperties properties;
    private final Clock clock;

    void take(long userId) {
        ZonedDateTime now = clock.instant().atZone(SEOUL);
        LocalDate day = now.toLocalDate();
        long secondsToMidnight = secondsToMidnight(now);
        try {
            // the user's own limit first, so someone at their limit doesn't use up everyone else's
            if (count(KEY_PREFIX + "user:" + userId + ":" + day) > properties.perUserDaily()
                    || count(KEY_PREFIX + "all:" + day) > properties.globalDaily()) {
                throw new TooManyRequestsException(secondsToMidnight);
            }
        } catch (DataAccessException e) {
            throw new ServiceUnavailableException("the AI guide is unavailable, try again later");
        }
    }

    static long secondsToMidnight(ZonedDateTime now) {
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(SEOUL)).toSeconds();
    }

    // EXPIRE on every count, not only the first: if it ever failed once, the key would otherwise live forever
    private long count(String key) {
        Long value = redis.opsForValue().increment(key);
        redis.expire(key, TTL);
        return value == null ? Long.MAX_VALUE : value;
    }
}
