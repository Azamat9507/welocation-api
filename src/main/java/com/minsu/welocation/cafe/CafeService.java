package com.minsu.welocation.cafe;

import com.minsu.welocation.cafe.CafeDtos.CafeResponse;
import com.minsu.welocation.cafe.CafeDtos.CreateCafeRequest;
import com.minsu.welocation.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CafeService {

    private final CafeRepository cafeRepository;

    @Transactional
    public CafeResponse create(CreateCafeRequest request) {
        Cafe cafe = cafeRepository.save(new Cafe(request.name(), request.address()));
        return CafeResponse.from(cafe);
    }

    public CafeResponse get(Long id) {
        return CafeResponse.from(find(id));
    }

    public Cafe find(Long id) {
        return cafeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("cafe " + id + " not found"));
    }
}
