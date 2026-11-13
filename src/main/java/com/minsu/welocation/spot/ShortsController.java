package com.minsu.welocation.spot;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.ShortsItem;
import com.minsu.welocation.spot.SpotDtos.ShortsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ShortsController {

    private final ShortsService shortsService;

    @Operation(summary = "Spots that have a YouTube Shorts video, newest video first")
    @GetMapping("/api/spots/shorts")
    public PageResponse<ShortsItem> feed(@Valid @ParameterObject @ModelAttribute PageParams params) {
        return shortsService.feed(params);
    }

    // under /api/admin, not /api/spots/**: GET there is public and public paths don't read the token
    @Operation(summary = "Attach a YouTube Shorts video to a spot (admins only)", security = @SecurityRequirement(name = "bearer"))
    @PutMapping("/api/admin/spots/{spotId}/shorts")
    public ShortsItem set(@PathVariable Long spotId, @Valid @RequestBody ShortsRequest request,
                          @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return shortsService.set(spotId, request.url(), CurrentUser.from(auth));
    }

    @Operation(summary = "Remove a spot's Shorts video (admins only)", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/admin/spots/{spotId}/shorts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        shortsService.remove(spotId, CurrentUser.from(auth));
    }
}
