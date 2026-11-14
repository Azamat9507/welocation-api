package com.minsu.welocation.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpotLikeRepository extends JpaRepository<SpotLike, Long> {

    boolean existsByUserIdAndSpotId(Long userId, Long spotId);

    @Modifying
    @Query("delete from SpotLike l where l.user.id = :userId and l.spot.id = :spotId")
    int deleteByUserAndSpot(long userId, long spotId);

    @Query("select l.createdAt from SpotLike l where l.user.id = :userId and l.spot.id = :spotId")
    Optional<Instant> findCreatedAt(long userId, long spotId);

    // every like of one user, in spot order so that deleting them locks spots in a fixed order
    @Query("select l.spot.id as spotId, l.createdAt as createdAt from SpotLike l where l.user.id = :userId order by l.spot.id")
    List<UserLike> findByUser(long userId);

    interface UserLike {
        Long getSpotId();

        Instant getCreatedAt();
    }

    // Ties are broken by the spot id as text, descending: the order Redis gives equal scores in a sorted set,
    // so the weekly ranking reads the same from either.
    @Query(value = """
            SELECT spot_id AS spotId, COUNT(*) AS likes
            FROM spot_likes
            WHERE created_at >= :from AND created_at < :to
            GROUP BY spot_id
            ORDER BY likes DESC, CAST(spot_id AS CHAR) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<LikeCount> countBetween(Instant from, Instant to, int limit);

    interface LikeCount {
        Long getSpotId();

        long getLikes();
    }
}
