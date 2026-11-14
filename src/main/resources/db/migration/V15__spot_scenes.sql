-- Dramas and films shot at a spot, one row per spot and work. Filled from the curated
-- resources/scenes/scenes.json (see SceneImporter); a spot's scenes go with it when it is deleted.
-- Filming locations TourAPI doesn't list get their own source: an admin can't edit or delete them as MANUAL
-- spots, because the next import would bring a deleted one back. scenes.json is where they change.
ALTER TABLE spots MODIFY source ENUM ('MANUAL', 'TOURAPI', 'SCENE') NOT NULL;

CREATE TABLE spot_scenes (
    id      BIGINT                   NOT NULL AUTO_INCREMENT,
    spot_id BIGINT                   NOT NULL,
    work    VARCHAR(100)             NOT NULL,
    kind    ENUM ('DRAMA', 'MOVIE')  NOT NULL,
    scene   VARCHAR(500),
    PRIMARY KEY (id),
    CONSTRAINT uk_spot_scene UNIQUE (spot_id, work),
    CONSTRAINT fk_spot_scene_spot FOREIGN KEY (spot_id) REFERENCES spots (id) ON DELETE CASCADE,
    INDEX idx_spot_scene_work (work)
) ENGINE = InnoDB;
