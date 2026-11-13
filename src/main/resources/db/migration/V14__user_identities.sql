-- Sign-in with Google and Kakao. A user signed up that way has no password, and a Kakao account may give no
-- email (Kakao only shares it with apps it has reviewed), so both columns may now be empty. MySQL's unique key
-- allows any number of NULL emails.
ALTER TABLE users
    MODIFY email VARCHAR(254) COLLATE utf8mb4_bin NULL,
    MODIFY password_hash VARCHAR(100) NULL;

-- One row per outside account linked to a user: the provider's stable id for that person, never their email
CREATE TABLE user_identities (
    id         BIGINT                   NOT NULL AUTO_INCREMENT,
    user_id    BIGINT                   NOT NULL,
    provider   ENUM ('GOOGLE', 'KAKAO') NOT NULL,
    subject    VARCHAR(100)             NOT NULL,
    created_at DATETIME(6)              NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT fk_identity_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_identity_user (user_id)
) ENGINE = InnoDB;
