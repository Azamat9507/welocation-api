package com.minsu.welocation.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // Each review shows its author's nickname. Without the graph that is one extra query per review;
    // with it the authors come in the same join. Safe to page: author is to-one, rows don't multiply.
    @EntityGraph(attributePaths = "author")
    Page<Review> findBySpotId(Long spotId, Pageable pageable);

    boolean existsBySpotIdAndAuthorId(Long spotId, Long authorId);

    // one review per author and spot (uk_review_author_spot); the author comes in the same query
    @EntityGraph(attributePaths = "author")
    Optional<Review> findBySpotIdAndAuthorId(Long spotId, Long authorId);

    // one grouped query for a whole page of spots instead of one per spot
    @Query("""
            select r.spot.id as spotId, count(r) as reviewCount, avg(r.rating) as averageRating
            from Review r
            where r.spot.id in :spotIds
            group by r.spot.id
            """)
    List<RatingSummary> summarize(Collection<Long> spotIds);

    @Modifying
    @Query("delete from Review r where r.author.id = :authorId")
    int deleteAllByAuthor(long authorId);

    interface RatingSummary {
        Long getSpotId();

        long getReviewCount();

        double getAverageRating();
    }
}
