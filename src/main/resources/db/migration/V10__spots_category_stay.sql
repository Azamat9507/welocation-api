-- STAY: waterside accommodation from TourAPI 숙박. Appended at the end of the list because MySQL can
-- only add an ENUM value without rebuilding the table when it goes last.
ALTER TABLE spots
    MODIFY COLUMN category ENUM ('ATTRACTION','BEACH','CAFE','LAKE','RIVER','VIEWPOINT','STAY') NOT NULL;
