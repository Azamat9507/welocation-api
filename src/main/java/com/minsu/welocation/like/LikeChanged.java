package com.minsu.welocation.like;

import java.time.Instant;

// likedAt is when the like was first made, also for an unlike, so a listener can tell which week it belongs to
public record LikeChanged(long spotId, Instant likedAt, int delta) {
}
