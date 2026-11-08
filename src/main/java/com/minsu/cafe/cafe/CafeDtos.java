package com.minsu.cafe.cafe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CafeDtos {

    private CafeDtos() {
    }

    public record CreateCafeRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String address) {
    }

    public record CafeResponse(Long id, String name, String address) {

        static CafeResponse from(Cafe cafe) {
            return new CafeResponse(cafe.getId(), cafe.getName(), cafe.getAddress());
        }
    }
}
