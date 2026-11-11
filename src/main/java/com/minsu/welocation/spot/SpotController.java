package com.minsu.welocation.spot;

import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import com.minsu.welocation.spot.SpotDtos.NearbySpot;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/spots")
@RequiredArgsConstructor
public class SpotController {

    private final SpotService spotService;

    @GetMapping
    public PageResponse<SpotSummary> search(@Valid @ModelAttribute SpotSearch search) {
        return spotService.search(search);
    }

    @GetMapping("/nearby")
    public List<NearbySpot> nearby(@Valid @ModelAttribute NearbySearch search) {
        return spotService.nearby(search);
    }

    @GetMapping("/{id}")
    public SpotDetail get(@PathVariable Long id) {
        return spotService.get(id);
    }
}
