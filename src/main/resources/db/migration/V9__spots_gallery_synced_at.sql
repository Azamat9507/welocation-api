-- When the TourAPI gallery (detailImage2) was last fetched for a spot. NULL = not yet, so the nightly
-- job can pick the next batch by index instead of scanning; the daily request quota only allows a
-- few hundred spots a night.
ALTER TABLE spots
    ADD COLUMN gallery_synced_at DATETIME(6) NULL,
    ADD INDEX idx_spots_gallery_synced_at (gallery_synced_at, id);
