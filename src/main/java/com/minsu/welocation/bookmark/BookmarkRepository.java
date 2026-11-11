package com.minsu.welocation.bookmark;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    // spots come in the same join, not one query per bookmark
    @EntityGraph(attributePaths = "spot")
    Page<Bookmark> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserIdAndSpotId(Long userId, Long spotId);

    @Modifying
    @Query("delete from Bookmark b where b.user.id = :userId and b.spot.id = :spotId")
    int deleteByUserAndSpot(long userId, long spotId);
}
