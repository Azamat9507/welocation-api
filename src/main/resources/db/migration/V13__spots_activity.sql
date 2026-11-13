-- What a LEISURE spot is for (karting, rafting, archery...). NULL for everything else and until the nightly
-- sync fills it in from each spot's TourAPI code; admins set it on the spots they add by hand.
ALTER TABLE spots
    ADD COLUMN activity ENUM ('KART','SHOOTING','ARCHERY','PAINTBALL','SURVIVAL','ATV','OFF_ROAD','BUNGEE','JET_SKI',
                              'KAYAK','RAFTING','SKYDIVING','PARAGLIDING','BALLOON','THEME_PARK') NULL,
    ADD INDEX idx_spots_activity (activity);
