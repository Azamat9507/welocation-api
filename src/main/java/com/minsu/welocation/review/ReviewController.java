package com.minsu.welocation.review;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.review.ReviewDtos.ReviewPage;
import com.minsu.welocation.review.ReviewDtos.ReviewRequest;
import com.minsu.welocation.review.ReviewDtos.ReviewResponse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Reviews of a spot, newest first")
    @GetMapping("/api/spots/{spotId}/reviews")
    public PageResponse<ReviewResponse> list(@PathVariable Long spotId, @Valid @ParameterObject @ModelAttribute ReviewPage page) {
        return reviewService.list(spotId, page);
    }

    @Operation(summary = "Review a spot (one per user)", security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/api/spots/{spotId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse create(@PathVariable Long spotId, @Valid @RequestBody ReviewRequest request,
                                 @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return reviewService.create(spotId, CurrentUser.from(auth), request);
    }

    @Operation(summary = "Edit your review", security = @SecurityRequirement(name = "bearer"))
    @PutMapping("/api/reviews/{reviewId}")
    public ReviewResponse update(@PathVariable Long reviewId, @Valid @RequestBody ReviewRequest request,
                                 @Parameter(hidden = true) JwtAuthenticationToken auth) {
        return reviewService.update(reviewId, CurrentUser.from(auth), request);
    }

    @Operation(summary = "Delete your review", security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/api/reviews/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long reviewId, @Parameter(hidden = true) JwtAuthenticationToken auth) {
        reviewService.delete(reviewId, CurrentUser.from(auth));
    }
}
