package com.minsu.welocation.review;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.ConflictException;
import com.minsu.welocation.common.Constraints;
import com.minsu.welocation.common.ForbiddenException;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.review.ReviewDtos.ReviewRequest;
import com.minsu.welocation.review.ReviewDtos.ReviewResponse;
import com.minsu.welocation.spot.Spot;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.user.Role;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final SpotRepository spotRepository;
    private final UserRepository userRepository;

    public PageResponse<ReviewResponse> list(long spotId, PageParams page) {
        if (!spotRepository.existsById(spotId)) {
            throw new NotFoundException("spot " + spotId + " not found");
        }
        PageRequest pageable = PageRequest.of(page.pageOrDefault(), page.sizeOrDefault(), Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(reviewRepository.findBySpotId(spotId, pageable).map(ReviewResponse::from));
    }

    @Transactional
    public ReviewResponse create(long spotId, CurrentUser me, ReviewRequest request) {
        Spot spot = spotRepository.findById(spotId)
                .orElseThrow(() -> new NotFoundException("spot " + spotId + " not found"));
        // a valid token for an account that no longer exists
        User author = userRepository.findById(me.id())
                .orElseThrow(() -> new UnauthorizedException("missing or invalid token"));
        if (reviewRepository.existsBySpotIdAndAuthorId(spotId, me.id())) {
            throw new ConflictException("you already reviewed this spot; edit that review instead");
        }
        try {
            Review review = reviewRepository.saveAndFlush(
                    new Review(spot, author, request.rating(), visibleText(request.content())));
            return ReviewResponse.from(review);
        } catch (DataIntegrityViolationException e) {
            // two posts at once from the same user: the unique key stops the second
            if (Constraints.isViolationOf(e, "uk_review_author_spot")) {
                throw new ConflictException("you already reviewed this spot; edit that review instead");
            }
            throw e;
        }
    }

    // Only the author edits: an admin rewriting text would put words under someone else's name.
    @Transactional
    public ReviewResponse update(long reviewId, CurrentUser me, ReviewRequest request) {
        Review review = findReview(reviewId);
        if (!review.isWrittenBy(me.id())) {
            throw new ForbiddenException("only the author can edit this review");
        }
        review.edit(request.rating(), visibleText(request.content()));
        // @UpdateTimestamp is set on flush; without it the response would carry the old updatedAt
        reviewRepository.flush();
        return ReviewResponse.from(review);
    }

    // The author, or an admin removing it as moderation.
    @Transactional
    public void delete(long reviewId, CurrentUser me) {
        Review review = findReview(reviewId);
        if (!review.isWrittenBy(me.id()) && !isAdmin(me)) {
            throw new ForbiddenException("only the author can delete this review");
        }
        reviewRepository.delete(review);
    }

    private Review findReview(long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("review " + reviewId + " not found"));
    }

    // read from the database, not the token: a demoted admin must lose this right immediately
    private boolean isAdmin(CurrentUser me) {
        return userRepository.findById(me.id()).map(user -> user.getRole() == Role.ADMIN).orElse(false);
    }

    // @NotBlank lets through text made only of no-break or zero-width spaces
    private static String visibleText(String content) {
        String text = content.strip();
        boolean visible = text.codePoints().anyMatch(cp ->
                !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && Character.getType(cp) != Character.FORMAT);
        if (!visible) {
            throw new BadRequestException("content: must not be blank");
        }
        return text;
    }
}
