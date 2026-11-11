CREATE TABLE spots (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(200) NOT NULL,
    category      ENUM ('ATTRACTION','BEACH','CAFE','LAKE','RIVER','VIEWPOINT') NOT NULL,
    region        ENUM ('BUSAN','CHUNGBUK','CHUNGNAM','DAEGU','DAEJEON','GANGWON','GWANGJU','GYEONGBUK',
                        'GYEONGGI','GYEONGNAM','INCHEON','JEJU','JEONBUK','JEONNAM','SEJONG','SEOUL','ULSAN') NOT NULL,
    address       VARCHAR(300) NOT NULL,
    latitude      DOUBLE       NOT NULL,
    longitude     DOUBLE       NOT NULL,
    description   TEXT,
    thumbnail_url VARCHAR(500),
    source        ENUM ('MANUAL','TOURAPI') NOT NULL,
    external_id   VARCHAR(50),
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_spot_source_external UNIQUE (source, external_id),
    INDEX idx_spot_region_category (region, category)
) ENGINE = InnoDB;

CREATE TABLE spot_tags (
    spot_id BIGINT NOT NULL,
    tag     ENUM ('KIDS','LAKE_VIEW','NIGHT_VIEW','OCEAN_VIEW','PARKING','PET_FRIENDLY','RIVER_VIEW','ROOFTOP') NOT NULL,
    PRIMARY KEY (spot_id, tag),
    CONSTRAINT fk_spot_tags_spot FOREIGN KEY (spot_id) REFERENCES spots (id)
) ENGINE = InnoDB;

CREATE TABLE spot_images (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    spot_id    BIGINT       NOT NULL,
    url        VARCHAR(500) NOT NULL,
    sort_order INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_spot_images_spot FOREIGN KEY (spot_id) REFERENCES spots (id)
) ENGINE = InnoDB;
