package com.minsu.welocation.spot;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

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
