package com.minsu.welocation.menu;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MenuDtos {

    private MenuDtos() {
    }

    public record CreateMenuRequest(
            @NotBlank @Size(max = 100) String name,
            @Min(0) @Max(1_000_000) int price) {
    }

    public record MenuResponse(Long id, String name, int price, boolean available) {

        static MenuResponse from(Menu menu) {
            return new MenuResponse(menu.getId(), menu.getName(), menu.getPrice(), menu.isAvailable());
        }
    }
}
