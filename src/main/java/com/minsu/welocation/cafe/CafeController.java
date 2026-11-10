package com.minsu.welocation.cafe;

import com.minsu.welocation.cafe.CafeDtos.CafeResponse;
import com.minsu.welocation.cafe.CafeDtos.CreateCafeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cafes")
@RequiredArgsConstructor
public class CafeController {

    private final CafeService cafeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CafeResponse create(@Valid @RequestBody CreateCafeRequest request) {
        return cafeService.create(request);
    }

    @GetMapping("/{id}")
    public CafeResponse get(@PathVariable Long id) {
        return cafeService.get(id);
    }
}
