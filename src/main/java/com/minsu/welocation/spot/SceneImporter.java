package com.minsu.welocation.spot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

// Loads the curated drama and film locations (scenes.json, built from 한국문화정보원's 미디어콘텐츠 영상 촬영지 data)
// into spots and spot_scenes. Safe to run on every start: a spot is found by source and external id, a scene by
// spot and work, and only what is missing is added. Nothing is updated or removed: a spot
// that is already there (synced from TourAPI earlier) is kept as it is.
// TourAPI places come in as TOURAPI spots with the list photo, and the nightly gallery sync adds the rest.
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneImporter {

    private final SceneSpotRepository spotRepository;
    private final SpotSceneRepository sceneRepository;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    @Value("${welocation.scenes.file:classpath:scenes/scenes.json}")
    private Resource file;

    record Place(SpotSource source, String externalId, String name, Region region, String address,
                 double latitude, double longitude, String image, List<SceneRow> scenes) {
    }

    record SceneRow(String work, SceneKind kind, String scene) {
    }

    public record Report(int spotsAdded, int scenesAdded, int failed) {
    }

    // one transaction per place, so a place that fails rolls back alone and the rest still load
    public Report importAll() throws IOException {
        Place[] places;
        try (InputStream in = file.getInputStream()) {
            places = json.readValue(in, Place[].class);
        }
        int spotsAdded = 0;
        int scenesAdded = 0;
        int failed = 0;
        for (Place place : places) {
            try {
                int[] added = tx.execute(status -> importPlace(place));
                spotsAdded += added[0];
                scenesAdded += added[1];
            } catch (RuntimeException e) {
                failed++;
                log.warn("scene import skipped {} {}: {}", place.source(), place.externalId(), e.getClass().getSimpleName());
            }
        }
        Report report = new Report(spotsAdded, scenesAdded, failed);
        log.info("scene import: {}", report);
        return report;
    }

    // {spots added, scenes added}
    private int[] importPlace(Place place) {
        Optional<Spot> existing = spotRepository.findBySourceAndExternalId(place.source(), place.externalId());
        Spot spot = existing.orElseGet(() -> spotRepository.save(newSpot(place)));
        int scenes = 0;
        for (SceneRow row : place.scenes()) {
            if (!sceneRepository.existsBySpotIdAndWork(spot.getId(), row.work())) {
                sceneRepository.save(new SpotScene(spot, row.work(), row.kind(), row.scene()));
                scenes++;
            }
        }
        return new int[]{existing.isEmpty() ? 1 : 0, scenes};
    }

    private static Spot newSpot(Place place) {
        Spot spot = new Spot(place.name(), SpotCategory.ATTRACTION, place.region(), place.address(),
                place.latitude(), place.longitude(), place.source(), place.externalId());
        if (place.image() != null) {
            spot.addImage(place.image());
        }
        return spot;
    }
}
