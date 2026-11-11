package com.minsu.welocation.like;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SpotLikeRepository extends JpaRepository<SpotLike, Long> {

    boolean existsByUserIdAndSpotId(Long userId, Long spotId);

    @Modifying
    @Query("delete from SpotLike l where l.user.id = :userId and l.spot.id = :spotId")
    int deleteByUserAndSpot(long userId, long spotId);
}
