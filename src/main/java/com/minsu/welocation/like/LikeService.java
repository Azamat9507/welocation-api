package com.minsu.welocation.like;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

// Liking twice is not an error: both calls end with the spot liked, and the count goes up once.
// See TASK.md T5 for how this compares with no lock, a bare atomic UPDATE and Redis under 1,000 threads.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LikeService {

    private final SpotLikeRepository likeRepository;
    private final SpotRepository spotRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;

    @Transactional
    public LikeResponse like(long spotId, CurrentUser me) {
        int likeCount = lockSpot(spotId);
        // a valid token for an account that no longer exists
        if (!userRepository.existsById(me.id())) {
            throw new UnauthorizedException("missing or invalid token");
        }
        if (!likeRepository.existsByUserIdAndSpotId(me.id(), spotId)) {
            SpotLike like = likeRepository.save(new SpotLike(userRepository.getReferenceById(me.id()), spotRepository.getReferenceById(spotId)));
            spotRepository.addLikes(spotId, 1);
            likeCount++;
            events.publishEvent(new LikeChanged(spotId, like.getCreatedAt(), 1));
        }
        return new LikeResponse(true, likeCount);
    }

    @Transactional
    public LikeResponse unlike(long spotId, CurrentUser me) {
        int likeCount = lockSpot(spotId);
        Optional<Instant> likedAt = likeRepository.findCreatedAt(me.id(), spotId);
        if (likedAt.isPresent()) {
            likeRepository.deleteByUserAndSpot(me.id(), spotId);
            spotRepository.addLikes(spotId, -1);
            likeCount--;
            events.publishEvent(new LikeChanged(spotId, likedAt.get(), -1));
        }
        return new LikeResponse(false, likeCount);
    }

    // Has to be the first statement. Under REPEATABLE READ the snapshot is taken at the first plain
    // SELECT; taken before the lock, it would miss a like committed while this request was waiting.
    private int lockSpot(long spotId) {
        return spotRepository.lockLikeCount(spotId)
                .orElseThrow(() -> new NotFoundException("spot " + spotId + " not found"));
    }

    public record LikeResponse(boolean liked, int likeCount) {
    }
}
