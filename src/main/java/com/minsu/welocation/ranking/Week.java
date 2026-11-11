package com.minsu.welocation.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;

// An ISO week in Korean time: Monday 00:00 KST up to the next Monday. The label uses the ISO week-based
// year, so 2027-01-01 (a Friday) is still in 2026-W53.
record Week(String label, Instant start, Instant end) {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    static Week of(Instant at) {
        ZonedDateTime start = at.atZone(SEOUL).toLocalDate().with(DayOfWeek.MONDAY).atStartOfDay(SEOUL);
        String label = "%d-W%02d".formatted(start.get(IsoFields.WEEK_BASED_YEAR), start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        return new Week(label, start.toInstant(), start.plusWeeks(1).toInstant());
    }

    boolean contains(Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }
}
