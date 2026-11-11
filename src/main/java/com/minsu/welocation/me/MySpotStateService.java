package com.minsu.welocation.me;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.bookmark.BookmarkRepository;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.like.SpotLikeRepository;
import com.minsu.welocation.review.ReviewRepository;
import com.minsu.welocation.spot.SpotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// What a spot page needs to know about the signed-in user. Each part is one lookup on a unique key.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MySpotStateService {

    private final SpotRepository spotRepository;
    private final SpotLikeRepository likeRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ReviewRepository reviewRepository;

    public MySpotState of(long spotId, CurrentUser me) {
        if (!spotRepository.existsById(spotId)) {
            throw new NotFoundException("spot " + spotId + " not found");
        }
        return new MySpotState(
                likeRepository.existsByUserIdAndSpotId(me.id(), spotId),
                bookmarkRepository.existsByUserIdAndSpotId(me.id(), spotId),
                reviewRepository.findIdBySpotAndAuthor(spotId, me.id()).orElse(null));
    }

    // reviewId is null while the user hasn't reviewed the spot
    public record MySpotState(boolean liked, boolean bookmarked, Long reviewId) {
    }
}
