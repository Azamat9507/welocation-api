package com.minsu.welocation.tourapi;

import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotSource;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

// kept apart from SpotRepository so the sync's lookup doesn't widen the public read API
interface TourApiSpotRepository extends Repository<Spot, Long> {

    List<Spot> findBySourceAndExternalIdIn(SpotSource source, Collection<String> externalIds);

    Spot save(Spot spot);
}
