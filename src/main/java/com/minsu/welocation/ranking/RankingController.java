package com.minsu.welocation.ranking;

import com.minsu.welocation.ranking.RankingDtos.WeeklyRanking;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RankingController {

    private final WeeklyRankingService rankingService;

    @Operation(summary = "Top 10 spots by likes given this week (Monday 00:00 KST onwards)")
    @GetMapping("/api/spots/ranking/weekly")
    public WeeklyRanking weekly() {
        return rankingService.top();
    }
}
