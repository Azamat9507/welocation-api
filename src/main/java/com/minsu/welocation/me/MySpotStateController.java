package com.minsu.welocation.me;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.me.MySpotStateService.MySpotState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// Under /api/me rather than /api/spots/{id}/me: GET /api/spots/** is public, and public paths don't read the token.
@RestController
@RequiredArgsConstructor
public class MySpotStateController {

    private final MySpotStateService stateService;

    @Operation(summary = "Whether I liked and bookmarked a spot, and my review of it",
            security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/api/me/spots/{spotId}")
    public MySpotState state(@PathVariable Long spotId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return stateService.of(spotId, CurrentUser.from(auth));
    }
}
