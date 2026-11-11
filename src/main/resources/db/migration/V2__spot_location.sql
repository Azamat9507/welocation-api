-- Derived from latitude/longitude so the entity never has to write it. A spatial index needs a
-- NOT NULL column with an SRID; STORED because InnoDB can't spatially index a virtual column.
-- POINT(x, y) takes longitude first; MySQL keeps it as lat/long internally for SRID 4326.
ALTER TABLE spots
    ADD COLUMN location POINT GENERATED ALWAYS AS (ST_SRID(POINT(longitude, latitude), 4326)) STORED SRID 4326 NOT NULL,
    ADD SPATIAL INDEX idx_spot_location (location);
