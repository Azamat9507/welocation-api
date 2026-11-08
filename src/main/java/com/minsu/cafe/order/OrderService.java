package com.minsu.cafe.order;

import com.minsu.cafe.cafe.Cafe;
import com.minsu.cafe.cafe.CafeService;
import com.minsu.cafe.common.BadRequestException;
import com.minsu.cafe.common.NotFoundException;
import com.minsu.cafe.menu.Menu;
import com.minsu.cafe.menu.MenuRepository;
import com.minsu.cafe.order.OrderDtos.CreateOrderRequest;
import com.minsu.cafe.order.OrderDtos.LineRequest;
import com.minsu.cafe.order.OrderDtos.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final MenuRepository menuRepository;
    private final CafeService cafeService;

    // Prices always come from the database, never from the client.
    @Transactional
    public OrderResponse place(CreateOrderRequest request) {
        Cafe cafe = cafeService.find(request.cafeId());
        Map<Long, Menu> menus = menuRepository
                .findAllById(request.items().stream().map(LineRequest::menuId).toList())
                .stream()
                .collect(Collectors.toMap(Menu::getId, Function.identity()));

        CafeOrder order = new CafeOrder(cafe);
        for (LineRequest line : request.items()) {
            Menu menu = menus.get(line.menuId());
            if (menu == null || !menu.getCafe().getId().equals(cafe.getId())) {
                throw new BadRequestException("menu " + line.menuId() + " is not on this cafe's menu");
            }
            if (!menu.isAvailable()) {
                throw new BadRequestException("menu " + line.menuId() + " is sold out");
            }
            order.addItem(new OrderItem(menu, line.quantity()));
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    public OrderResponse get(Long id) {
        CafeOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("order " + id + " not found"));
        return OrderResponse.from(order);
    }
}
