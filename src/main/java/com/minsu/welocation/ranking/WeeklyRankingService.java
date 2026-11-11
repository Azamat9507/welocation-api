package com.minsu.welocation.ranking;

import com.minsu.welocation.like.LikeChanged;
import com.minsu.welocation.like.SpotLikeRepository;
import com.minsu.welocation.ranking.RankingDtos.RankedSpot;
import com.minsu.welocation.ranking.RankingDtos.WeeklyRanking;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import com.minsu.welocation.spot.SpotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// MySQL (spot_likes) is the source of truth; Redis keeps a sorted set per week so the ranking is one read.
// The set is updated after each like commits and refilled from MySQL every hour, when the key is missing
// (Redis restarted, or a new week) and on the first read after an update was missed or skipped. A like committed while a refill is
// running can be counted once too many or too few until the next refill.
//
// The after-commit listener still runs inside the like request, with its database connection held. After a
// Redis failure Redis is left alone for `redis-backoff`, so a hung Redis costs one timeout, not one per like.
//
// No class-level @Transactional: Spring refuses an after-commit listener on a transactional bean.
@Slf4j
@Service
public class WeeklyRankingService {

    static final int SIZE = 10;
    static final String KEY_PREFIX = "ranking:weekly:";
    // a week's set is read for seven days; the rest is slack so it never expires mid-week
    static final Duration TTL = Duration.ofDays(15);

    private final StringRedisTemplate redis;
    private final SpotLikeRepository likeRepository;
    private final SpotService spotService;
    private final Clock clock;
    private final Duration redisBackoff;

    private volatile Instant redisSkippedUntil = Instant.MIN;
    private volatile boolean missedUpdates;

    public WeeklyRankingService(StringRedisTemplate redis, SpotLikeRepository likeRepository, SpotService spotService,
                                Clock clock, @Value("${welocation.ranking.redis-backoff}") Duration redisBackoff) {
        this.redis = redis;
        this.likeRepository = likeRepository;
        this.spotService = spotService;
        this.clock = clock;
        this.redisBackoff = redisBackoff;
    }

    public WeeklyRanking top() {
        Week week = Week.of(clock.instant());
        List<Score> scores = null;
        if (redisUsable()) {
            try {
                scores = fromRedis(week);
            } catch (DataAccessException e) {
                redisFailed("read", e);
            }
        }
        if (scores == null) {
            scores = fromMysql(week, SIZE);
        }
        Map<Long, SpotSummary> spots = spotService.summariesById(scores.stream().map(Score::spotId).toList());
        List<RankedSpot> items = new ArrayList<>();
        for (Score score : scores) {
            SpotSummary spot = spots.get(score.spotId());
            if (spot != null) {
                items.add(new RankedSpot(items.size() + 1, score.likes(), spot));
            }
        }
        return new WeeklyRanking(week.label(), items);
    }

    // Equal scores come back in reverse byte order of the member (the spot id as text);
    // SpotLikeRepository.countBetween orders the same way so both paths give the same list.
    private List<Score> fromRedis(Week week) {
        String key = KEY_PREFIX + week.label();
        // an empty week has no key either, so that case pays one small indexed count per read
        if (missedUpdates || !Boolean.TRUE.equals(redis.hasKey(key))) {
            refill(week);
        }
        Set<TypedTuple<String>> top = redis.opsForZSet()
                .reverseRangeByScoreWithScores(key, 1, Double.POSITIVE_INFINITY, 0, SIZE);
        if (top == null) {
            return List.of();
        }
        return top.stream()
                .map(t -> new Score(Long.parseLong(t.getValue()), t.getScore().longValue()))
                .toList();
    }

    private List<Score> fromMysql(Week week, int limit) {
        return likeRepository.countBetween(week.start(), week.end(), limit).stream()
                .map(c -> new Score(c.getSpotId(), c.getLikes()))
                .toList();
    }

    // Only the current week is ever read, so a like made last week and removed now changes nothing.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeChanged(LikeChanged change) {
        Week week = Week.of(clock.instant());
        if (!week.contains(change.likedAt())) {
            return;
        }
        if (!redisUsable()) {
            missedUpdates = true;
            return;
        }
        String key = KEY_PREFIX + week.label();
        try {
            // Incrementing a missing key would start a set holding only this spot, and the read would trust it.
            // Leave it to the next read's refill instead; MySQL already has this like.
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                missedUpdates = true;
                return;
            }
            redis.opsForZSet().incrementScore(key, String.valueOf(change.spotId()), change.delta());
            redis.expire(key, TTL);
        } catch (DataAccessException e) {
            // the like itself is committed; a refill puts the score right
            redisFailed("update for spot " + change.spotId(), e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${welocation.ranking.rebuild-cron}")
    public void rebuild() {
        try {
            refill(Week.of(clock.instant()));
        } catch (DataAccessException e) {
            redisFailed("rebuild", e);
        }
    }

    private void refill(Week week) {
        // cleared before counting, so an update missed while this runs triggers the next refill
        missedUpdates = false;
        String key = KEY_PREFIX + week.label();
        List<Score> scores = fromMysql(week, Integer.MAX_VALUE);
        if (scores.isEmpty()) {
            redis.delete(key);
            return;
        }
        // built aside and swapped in with RENAME, so readers never see a half-filled set
        String staging = key + ":rebuild:" + UUID.randomUUID();
        redis.opsForZSet().add(staging, scores.stream()
                .map(s -> TypedTuple.of(String.valueOf(s.spotId()), (double) s.likes()))
                .collect(Collectors.toSet()));
        redis.expire(staging, TTL);
        redis.rename(staging, key);
    }

    private boolean redisUsable() {
        return !clock.instant().isBefore(redisSkippedUntil);
    }

    private void redisFailed(String what, DataAccessException e) {
        missedUpdates = true;
        redisSkippedUntil = clock.instant().plus(redisBackoff);
        log.warn("weekly ranking {} failed, using MySQL for {}: {}", what, redisBackoff, e.getClass().getSimpleName());
    }

    private record Score(long spotId, long likes) {
    }
}
