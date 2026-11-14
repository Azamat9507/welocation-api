package com.minsu.welocation.spot;

import org.springframework.data.repository.Repository;

import java.util.Optional;

// the importer's lookup, kept out of SpotRepository like the TourAPI sync's own
interface SceneSpotRepository extends Repository<Spot, Long> {

    Optional<Spot> findBySourceAndExternalId(SpotSource source, String externalId);

    Spot save(Spot spot);
}
