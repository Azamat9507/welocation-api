package com.minsu.welocation.bookmark;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotService;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Adding and removing are idempotent, like likes.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookmarkService {

    private final BookmarkRepository bookmarkRepository;
    private final SpotRepository spotRepository;
    private final SpotService spotService;
    private final UserRepository userRepository;

    // Takes the same spot row lock as LikeService, for the same reason: without it a double click is two
    // inserts racing for the unique key. Locking the user row instead could deadlock against a like of
    // the same spot (each could hold one row and wait for the other).
    @Transactional
    public void add(long spotId, CurrentUser me) {
        if (spotRepository.lockLikeCount(spotId).isEmpty()) {
            throw new NotFoundException("spot " + spotId + " not found");
        }
        if (!userRepository.existsById(me.id())) {
            throw new UnauthorizedException("missing or invalid token");
        }
        if (!bookmarkRepository.existsByUserIdAndSpotId(me.id(), spotId)) {
            bookmarkRepository.save(new Bookmark(userRepository.getReferenceById(me.id()), spotRepository.getReferenceById(spotId)));
        }
    }

    @Transactional
    public void remove(long spotId, CurrentUser me) {
        if (!spotRepository.existsById(spotId)) {
            throw new NotFoundException("spot " + spotId + " not found");
        }
        bookmarkRepository.deleteByUserAndSpot(me.id(), spotId);
    }

    // newest bookmark first
    public PageResponse<SpotSummary> mine(CurrentUser me, PageParams page) {
        PageRequest pageable = PageRequest.of(page.pageOrDefault(), page.sizeOrDefault(), Sort.by(Sort.Direction.DESC, "id"));
        return spotService.summaries(bookmarkRepository.findByUserId(me.id(), pageable).map(Bookmark::getSpot));
    }
}
