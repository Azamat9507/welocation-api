package com.minsu.cafe.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotNull Long cafeId,
            @NotEmpty @Size(max = 20) List<@Valid LineRequest> items) {
    }

    public record LineRequest(
            @NotNull Long menuId,
            @Min(1) @Max(50) int quantity) {
    }

    public record OrderResponse(Long id, Long cafeId, OrderStatus status, int totalPrice,
                                Instant createdAt, List<LineResponse> items) {

        static OrderResponse from(CafeOrder order) {
            return new OrderResponse(order.getId(), order.getCafe().getId(), order.getStatus(),
                    order.getTotalPrice(), order.getCreatedAt(),
                    order.getItems().stream().map(LineResponse::from).toList());
        }
    }

    public record LineResponse(Long menuId, String name, int quantity, int unitPrice) {

        static LineResponse from(OrderItem item) {
            return new LineResponse(item.getMenu().getId(), item.getMenu().getName(),
                    item.getQuantity(), item.getUnitPrice());
        }
    }
}
