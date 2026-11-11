package com.minsu.welocation.spot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SpotRepository extends JpaRepository<Spot, Long>, JpaSpecificationExecutor<Spot> {

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
}
