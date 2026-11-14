package com.minsu.welocation.account;

import com.minsu.welocation.bookmark.BookmarkRepository;
import com.minsu.welocation.like.LikeChanged;
import com.minsu.welocation.like.SpotLikeRepository;
import com.minsu.welocation.like.SpotLikeRepository.UserLike;
import com.minsu.welocation.review.ReviewRepository;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.user.UserIdentityRepository;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Removes a user and everything they left: likes, bookmarks, reviews and linked sign-ins. A like also lowers
// the spot's count and goes out as a LikeChanged event, the same as an unlike, so the weekly ranking follows.
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final SpotLikeRepository likeRepository;
    private final SpotRepository spotRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ReviewRepository reviewRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public void delete(long userId) {
        // spot order (see findByUser), so two deletions or a deletion and a like never wait on each other in a circle
        for (UserLike like : likeRepository.findByUser(userId)) {
            long spotId = like.getSpotId();
            spotRepository.lockLikeCount(spotId);
            if (likeRepository.deleteByUserAndSpot(userId, spotId) > 0) {
                spotRepository.addLikes(spotId, -1);
                events.publishEvent(new LikeChanged(spotId, like.getCreatedAt(), -1));
            }
        }
        bookmarkRepository.deleteAllByUser(userId);
        reviewRepository.deleteAllByAuthor(userId);
        identityRepository.deleteAllByUser(userId);
        userRepository.deleteNow(userId);
    }
}
