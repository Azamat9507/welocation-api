-- One review per user per spot: a second one is an edit, not a new row. The unique key starts with
-- author_id so that the review list (WHERE spot_id = ? ORDER BY id DESC) picks idx_review_spot_newest;
-- with (spot_id, author_id) MySQL chose the unique key plus a filesort.
CREATE TABLE reviews (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    spot_id    BIGINT        NOT NULL,
    author_id  BIGINT        NOT NULL,
    rating     INT           NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_review_author_spot UNIQUE (author_id, spot_id),
    CONSTRAINT fk_reviews_spot FOREIGN KEY (spot_id) REFERENCES spots (id),
    CONSTRAINT fk_reviews_author FOREIGN KEY (author_id) REFERENCES users (id),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5),
    INDEX idx_review_spot_newest (spot_id, id)
) ENGINE = InnoDB;
