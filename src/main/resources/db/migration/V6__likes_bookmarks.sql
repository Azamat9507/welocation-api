-- V5 is reserved for the TourAPI sync branch.
-- like_count is a copy of COUNT(*) over spot_likes, kept so list pages can show and later sort by it
-- without counting. Only the like/unlike statements change it, in the same transaction as the row.
ALTER TABLE spots ADD COLUMN like_count INT NOT NULL DEFAULT 0;

CREATE TABLE spot_likes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    spot_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_like_user_spot UNIQUE (user_id, spot_id),
    CONSTRAINT fk_spot_likes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_spot_likes_spot FOREIGN KEY (spot_id) REFERENCES spots (id)
) ENGINE = InnoDB;

-- "my bookmarks" (WHERE user_id = ? ORDER BY id DESC) uses the unique key and sorts only that user's rows.
-- An extra (user_id, id) index was tried: EXPLAIN on 4,000 rows still picked the unique key, so it's not here.
CREATE TABLE bookmarks (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    spot_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bookmark_user_spot UNIQUE (user_id, spot_id),
    CONSTRAINT fk_bookmarks_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookmarks_spot FOREIGN KEY (spot_id) REFERENCES spots (id)
) ENGINE = InnoDB;
