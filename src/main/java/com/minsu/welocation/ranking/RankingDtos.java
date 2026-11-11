package com.minsu.welocation.ranking;

import com.minsu.welocation.spot.SpotDtos.SpotSummary;

import java.util.List;

public final class RankingDtos {

    private RankingDtos() {
    }

    public record WeeklyRanking(String week, List<RankedSpot> items) {
    }

    public record RankedSpot(int rank, long weeklyLikes, SpotSummary spot) {
    }
}
