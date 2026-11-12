-- LEISURE: karting, rafting, paragliding, theme parks and the like from TourAPI 레저스포츠 / 테마공원.
-- Appended last for the same reason as STAY in V10: MySQL adds a last ENUM value without a table rebuild.
ALTER TABLE spots
    MODIFY COLUMN category ENUM ('ATTRACTION','BEACH','CAFE','LAKE','RIVER','VIEWPOINT','STAY','LEISURE') NOT NULL;
