package com.minsu.welocation.like;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.like.LikeService.LikeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @Operation(summary = "Like a spot (liking again changes nothing)", security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/api/spots/{spotId}/like")
    public LikeResponse like(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return likeService.like(spotId, CurrentUser.from(auth));
    }

    @Operation(summary = "Take back your like", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/spots/{spotId}/like")
    public LikeResponse unlike(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return likeService.unlike(spotId, CurrentUser.from(auth));
    }
}
