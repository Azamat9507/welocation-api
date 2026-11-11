package com.minsu.welocation.ranking;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WeekTest {

    @Test
    void weekStartsOnMondayMidnightKoreanTime() {
        // Sunday 23:59:59 KST is 14:59:59 UTC the same day; Monday 00:00 KST is 15:00 UTC on Sunday
        Week sunday = Week.of(Instant.parse("2026-11-15T14:59:59Z"));
        Week monday = Week.of(Instant.parse("2026-11-15T15:00:00Z"));

        assertThat(sunday.label()).isEqualTo("2026-W46");
        assertThat(monday.label()).isEqualTo("2026-W47");
        assertThat(sunday.end()).isEqualTo(monday.start()).isEqualTo(Instant.parse("2026-11-15T15:00:00Z"));
        assertThat(sunday.contains(Instant.parse("2026-11-15T14:59:59.999999Z"))).isTrue();
        assertThat(sunday.contains(sunday.end())).isFalse();
        assertThat(monday.contains(monday.start())).isTrue();
    }

    @Test
    void newYearsDayCanBelongToTheLastWeekOfTheYearBefore() {
        // 2027-01-01 is a Friday: ISO puts it in the week that started on Monday 2026-12-28
        Week week = Week.of(Instant.parse("2027-01-01T03:00:00Z"));

        assertThat(week.label()).isEqualTo("2026-W53");
        assertThat(week.start()).isEqualTo(Instant.parse("2026-12-27T15:00:00Z"));
        assertThat(Week.of(Instant.parse("2027-01-03T15:00:00Z")).label()).isEqualTo("2027-W01");
    }
}
