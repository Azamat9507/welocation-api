package com.minsu.welocation.spot;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SpotRepository extends JpaRepository<Spot, Long>, JpaSpecificationExecutor<Spot> {

    // the /shorts feed; idx_spots_shorts_added_at covers the filter and the order
    Page<Spot> findByShortsAddedAtIsNotNull(Pageable pageable);

    // MBRContains is the part that can use idx_spot_location; the distance check then trims the
    // box corners down to the actual circle.
    String NEARBY_SQL = """
            SELECT s.id, s.name, s.category, s.region, s.address, s.latitude, s.longitude,
                   s.thumbnail_url AS thumbnailUrl,
                   ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) AS distanceMeters
            FROM spots s
            WHERE MBRContains(ST_GeomFromText(:box, 4326, 'axis-order=long-lat'), s.location)
              AND ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) <= :radiusMeters
              AND (:category IS NULL OR s.category = :category)
            ORDER BY distanceMeters, s.id
            LIMIT :limit
            """;

    // Row lock held until commit, empty if the spot doesn't exist. Likes and bookmarks of one spot take
    // it first, so their "already there?" check and the write after it can't interleave with another
    // request's. While it is held nobody else can change like_count, so the value read here stays exact.
    @Query(value = "SELECT like_count FROM spots WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockLikeCount(long id);

    // one statement, so two transactions can't both read 5 and both write 6
    @Modifying
    @Query(value = "UPDATE spots SET like_count = like_count + :delta WHERE id = :id", nativeQuery = true)
    void addLikes(long id, int delta);

    @Query(value = NEARBY_SQL, nativeQuery = true)
    List<NearbyRow> findNearby(double lat, double lng, double radiusMeters, String box, String category, int limit);

    interface NearbyRow {
        Long getId();

        String getName();

        SpotCategory getCategory();

        Region getRegion();

        String getAddress();

        double getLatitude();

        double getLongitude();

        String getThumbnailUrl();

        double getDistanceMeters();
    }

    // Candidates for the AI guide. nameLike is already escaped for LIKE by the caller.
    @Query(value = """
            SELECT s.id AS id
            FROM spots s
            WHERE (:region IS NULL OR s.region = :region)
              AND (:category IS NULL OR s.category = :category)
              AND (:nameLike IS NULL OR s.name LIKE :nameLike)
            ORDER BY s.like_count DESC, s.has_image DESC, s.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Candidate> findCandidates(String region, String category, String nameLike, int limit);

    // Coordinates of every spot matching the filters, for the AI guide's "near a beach" search. Filtering pairs in
    // SQL was tried first: MySQL hash-joined all cafés with all beaches and computed ST_Distance_Sphere for each of the
    // 1.2 million pairs before any cheaper filter (4.9 s on the 3,715 local spots), so the distances are worked out
    // in Java instead.
    @Query(value = """
            SELECT s.id AS id, s.latitude AS latitude, s.longitude AS longitude
            FROM spots s
            WHERE (:region IS NULL OR s.region = :region)
              AND (:category IS NULL OR s.category = :category)
              AND (:nameLike IS NULL OR s.name LIKE :nameLike)
            """, nativeQuery = true)
    List<Located> findLocated(String region, String category, String nameLike);

    interface Candidate {
        Long getId();
    }

    interface Located {
        Long getId();

        double getLatitude();

        double getLongitude();
    }
}
