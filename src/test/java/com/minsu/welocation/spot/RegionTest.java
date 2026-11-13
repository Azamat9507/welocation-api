package com.minsu.welocation.spot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionTest {

    @Test
    void theProvinceComesFromTheFirstWordOfTheAddress() {
        assertThat(Region.fromAddress("경기 수원시 팔달구 창룡대로103번길 8")).contains(Region.GYEONGGI);
        assertThat(Region.fromAddress("서울특별시 종로구")).contains(Region.SEOUL);
        assertThat(Region.fromAddress("강원특별자치도 홍천군")).contains(Region.GANGWON);
        assertThat(Region.fromAddress("충청남도 예산군")).contains(Region.CHUNGNAM);
        assertThat(Region.fromAddress("전북특별자치도 전주시")).contains(Region.JEONBUK);
        assertThat(Region.fromAddress("  제주 서귀포시")).contains(Region.JEJU);
        // 광주시 in 경기도 starts with 경기, so it is not mistaken for 광주광역시
        assertThat(Region.fromAddress("경기 광주시 오포읍")).contains(Region.GYEONGGI);
        assertThat(Region.fromAddress("수원시 팔달구")).isEmpty();
        assertThat(Region.fromAddress("")).isEmpty();
    }
}
