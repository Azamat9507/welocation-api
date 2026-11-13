package com.minsu.welocation.spot;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.BadRequestException;
import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageParams;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.ShortsItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShortsService {

    private final SpotRepository spotRepository;
    private final AdminGuard adminGuard;

    public PageResponse<ShortsItem> feed(PageParams params) {
        PageRequest pageable = PageRequest.of(params.pageOrDefault(), params.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "shortsAddedAt", "id"));
        return PageResponse.from(spotRepository.findByShortsAddedAtIsNotNull(pageable).map(ShortsItem::from));
    }

    @Transactional
    public ShortsItem set(long spotId, String url, CurrentUser me) {
        requireAdmin(me);
        String videoId = YouTubeIds.parse(url)
                .orElseThrow(() -> new BadRequestException("url: not a YouTube video link"));
        Spot spot = findSpot(spotId);
        spot.setShorts(videoId, Instant.now(Clock.systemUTC()));
        return ShortsItem.from(spot);
    }

    @Transactional
    public void remove(long spotId, CurrentUser me) {
        requireAdmin(me);
        findSpot(spotId).setShorts(null, null);
    }

    private Spot findSpot(long spotId) {
        return spotRepository.findById(spotId)
                .orElseThrow(() -> new NotFoundException("spot " + spotId + " not found"));
    }

    private void requireAdmin(CurrentUser me) {
        adminGuard.require(me);
    }
}
