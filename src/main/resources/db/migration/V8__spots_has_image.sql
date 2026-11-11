-- List pages put spots with a photo first: about half of the TourAPI cafés have none, and a first page of empty
-- cards looks broken. The flag is derived from thumbnail_url so it can't drift, and (has_image, id) lets the
-- unfiltered first page read in index order instead of sorting the table.
ALTER TABLE spots
    ADD COLUMN has_image BOOLEAN AS (thumbnail_url IS NOT NULL) STORED NOT NULL,
    ADD INDEX idx_spots_has_image_id (has_image, id);
