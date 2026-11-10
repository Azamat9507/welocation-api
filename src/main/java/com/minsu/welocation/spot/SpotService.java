package com.minsu.welocation.spot;

import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.PageResponse;
import com.minsu.welocation.spot.SpotDtos.SpotDetail;
import com.minsu.welocation.spot.SpotDtos.SpotSearch;
import com.minsu.welocation.spot.SpotDtos.SpotSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpotService {

    private final SpotRepository spotRepository;

    public PageResponse<SpotSummary> search(SpotSearch search) {
        PageRequest pageable = PageRequest.of(search.pageOrDefault(), search.sizeOrDefault(),
                Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(spotRepository
                .findAll(SpotSpecs.matching(search.region(), search.category(), search.tag()), pageable)
                .map(SpotSummary::from));
    }

    public SpotDetail get(Long id) {
        Spot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("spot " + id + " not found"));
        return SpotDetail.from(spot);
    }
}
