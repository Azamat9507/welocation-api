package com.minsu.cafe.menu;

import com.minsu.cafe.cafe.Cafe;
import com.minsu.cafe.cafe.CafeService;
import com.minsu.cafe.menu.MenuDtos.CreateMenuRequest;
import com.minsu.cafe.menu.MenuDtos.MenuResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;
    private final CafeService cafeService;

    @Transactional
    public MenuResponse create(Long cafeId, CreateMenuRequest request) {
        Cafe cafe = cafeService.find(cafeId);
        Menu menu = menuRepository.save(new Menu(cafe, request.name(), request.price()));
        return MenuResponse.from(menu);
    }

    public List<MenuResponse> list(Long cafeId) {
        cafeService.find(cafeId);
        return menuRepository.findByCafeIdOrderById(cafeId).stream()
                .map(MenuResponse::from)
                .toList();
    }
}
