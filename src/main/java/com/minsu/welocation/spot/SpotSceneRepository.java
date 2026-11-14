package com.minsu.welocation.spot;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

interface SpotSceneRepository extends Repository<SpotScene, Long> {

    List<SpotScene> findBySpotIdOrderByIdAsc(Long spotId);

    List<SpotScene> findByWorkOrderByIdAsc(String work);

    boolean existsBySpotIdAndWork(Long spotId, String work);

    SpotScene save(SpotScene scene);

    // Every work with how many spots it has and one of their photos for the cover. MAX picks a photo
    // the same way on every call, so the cover doesn't change between requests.
    @Query("""
            SELECT s.work AS work, s.kind AS kind, COUNT(s) AS spots, MAX(p.thumbnailUrl) AS cover
            FROM SpotScene s JOIN s.spot p
            GROUP BY s.work, s.kind
            ORDER BY COUNT(s) DESC, s.work""")
    List<WorkRow> findWorks();

    interface WorkRow {
        String getWork();

        SceneKind getKind();

        long getSpots();

        String getCover();
    }
}
