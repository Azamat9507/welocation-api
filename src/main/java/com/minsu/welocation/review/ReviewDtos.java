package com.minsu.welocation.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record ReviewRequest(
            @NotNull @Min(1) @Max(5) Integer rating,
            @NotBlank @Size(max = 1000) String content) {
    }

    // nickname only: a review is public, the author's email is not
    public record Author(Long id, String nickname) {
    }

    public record ReviewResponse(Long id, int rating, String content, Author author,
                                 Instant createdAt, Instant updatedAt) {

        public static ReviewResponse from(Review review) {
            return new ReviewResponse(review.getId(), review.getRating(), review.getContent(),
                    new Author(review.getAuthor().getId(), review.getAuthor().getNickname()),
                    review.getCreatedAt(), review.getUpdatedAt());
        }
    }
}
