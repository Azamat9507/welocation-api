package com.minsu.welocation.guide;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.guide.GuideDtos.GuideRequest;
import com.minsu.welocation.guide.GuideDtos.GuideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GuideController {

    private final GuideService guideService;

    @Operation(summary = "Ask the AI guide; it answers only with spots from the database",
            description = "Answers in the language given in lang (ko, en, ru or uz; ko when left out); any other "
                    + "value is a 400. Limited per user and per day. 503 when the guide isn't set up or the model is unavailable.",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/api/guide")
    public GuideResponse ask(@Valid @RequestBody GuideRequest request, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return guideService.ask(request.question(), request.language(), CurrentUser.from(auth));
    }
}
