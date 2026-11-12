package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotSource;
import org.springframework.data.domain.Limit;
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
}
