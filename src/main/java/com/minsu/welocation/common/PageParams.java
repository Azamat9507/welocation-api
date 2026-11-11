package com.minsu.welocation.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// page capped so page * size stays a sane offset; huge values used to overflow into a 500
public record PageParams(@Min(0) @Max(10_000) Integer page, @Min(1) @Max(50) Integer size) {

    public int pageOrDefault() {
        return page == null ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
