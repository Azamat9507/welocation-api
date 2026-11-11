package com.minsu.welocation.spot;

import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.NearbySearch;
import com.minsu.welocation.spot.SpotDtos.NearbySpot;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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

    @Operation(summary = "List spots, filtered by region, category and tag; spots with a photo first, then newest")
    @GetMapping
    public PageResponse<SpotSummary> search(@Valid @ParameterObject @ModelAttribute SpotSearch search) {
        return spotService.search(search);
    }

    @Operation(summary = "Spots within radiusKm of a point, nearest first",
            description = "The caller's coordinates are used for this query only and are never stored or logged.")
    @GetMapping("/nearby")
    public List<NearbySpot> nearby(@Valid @ParameterObject @ModelAttribute NearbySearch search) {
        return spotService.nearby(search);
    }

    @Operation(summary = "Spot detail with tags and images")
    @GetMapping("/{id}")
    public SpotDetail get(@PathVariable Long id) {
        return spotService.get(id);
    }
}
