package com.minsu.welocation.spot;

import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.spot.SpotDtos.Work;
import com.minsu.welocation.spot.SpotDtos.WorkSpots;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

// Dramas and films shot at our spots. The list only changes when scenes.json does, so it may be cached for an hour.
@RestController
@RequestMapping("/api/scenes")
@RequiredArgsConstructor
public class SceneController {

    private final SpotService spotService;

    @Operation(summary = "Every drama and film with the number of its spots and a cover photo, most spots first")
    @GetMapping("/works")
    public ResponseEntity<List<Work>> works() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(spotService.works());
    }

    @Operation(summary = "The spots of one drama or film (exact Korean title), each with what happens there")
    @GetMapping
    public WorkSpots work(@RequestParam String work) {
        if (work.isBlank() || work.length() > SpotScene.MAX_WORK_LENGTH) {
            throw new BadRequestException("work must be 1 to " + SpotScene.MAX_WORK_LENGTH + " characters");
        }
        return spotService.work(work);
    }
}
