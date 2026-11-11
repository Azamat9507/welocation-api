package com.minsu.welocation.bookmark;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;

    @Operation(summary = "Bookmark a spot", security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/api/spots/{spotId}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        bookmarkService.add(spotId, CurrentUser.from(auth));
    }

    @Operation(summary = "Remove a bookmark", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/spots/{spotId}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        bookmarkService.remove(spotId, CurrentUser.from(auth));
    }

    @Operation(summary = "Your bookmarked spots, newest first", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/api/me/bookmarks")
    public PageResponse<SpotSummary> mine(@Valid @ParameterObject @ModelAttribute PageParams page,
                                          @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return bookmarkService.mine(CurrentUser.from(auth), page);
    }
}
