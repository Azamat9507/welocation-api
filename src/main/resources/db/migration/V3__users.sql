-- Binary collation on email: the default utf8mb4_0900_ai_ci ignores accents and width, so
-- "kïm@exämple.com" would find kim@example.com and slip past the per-email login limit.
-- Case is folded in the application (lowercase) before anything reaches the table.
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(254) COLLATE utf8mb4_bin NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    nickname      VARCHAR(30)  NOT NULL,
    role          ENUM ('ADMIN','USER') NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_email UNIQUE (email)
) ENGINE = InnoDB;
