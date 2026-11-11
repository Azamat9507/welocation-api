-- The weekly ranking counts one week of likes per spot (rebuild and the fallback when Redis is down).
-- With spot_id in the index the count is read from the index alone, without touching the rows.
CREATE INDEX idx_spot_likes_created ON spot_likes (created_at, spot_id);
