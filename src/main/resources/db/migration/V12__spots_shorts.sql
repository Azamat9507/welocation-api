-- One YouTube Shorts video per spot, picked by an admin. Only the 11-character video id is kept; the
-- player is YouTube's own embed. shorts_added_at orders the /shorts feed, newest first.
ALTER TABLE spots
    ADD COLUMN shorts_video_id VARCHAR(11) NULL,
    ADD COLUMN shorts_added_at DATETIME(6) NULL,
    ADD INDEX idx_spots_shorts_added_at (shorts_added_at, id);
