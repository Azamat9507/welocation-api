package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotSource;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// kept apart from SpotRepository so the sync's lookup doesn't widen the public read API
interface TourApiSpotRepository extends Repository<Spot, Long> {

    List<Spot> findBySourceAndExternalIdIn(SpotSource source, Collection<String> externalIds);

    Spot save(Spot spot);

    // next spots whose gallery was never fetched; only ones with a main photo; TourAPI has no gallery without it.
    // Newest first, the order of the home page list, so the spots people see first get their photos first.
    List<Spot> findBySourceAndGallerySyncedAtIsNullAndThumbnailUrlIsNotNullOrderByIdDesc(SpotSource source, Limit limit);

    Optional<Spot> findById(Long id);

    // One indexed probe per stay: MBRContains lets idx_spot_location narrow to the few spots in a ~2 km box,
    // the distance check trims that to the circle. Joining all stays with all water spots instead would
    // compute every pair (see SpotRepository.findLocated), and loading the water spots into memory would
    // mean keeping a second copy of the spatial index in Java.
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM spots s
                WHERE MBRContains(ST_GeomFromText(:box, 4326, 'axis-order=long-lat'), s.location)
                  AND s.category IN ('BEACH', 'LAKE', 'RIVER')
                  AND ST_Distance_Sphere(s.location, ST_SRID(POINT(:lng, :lat), 4326)) <= :radiusMeters)
            """, nativeQuery = true)
    long waterWithin(double lat, double lng, double radiusMeters, String box);
}
