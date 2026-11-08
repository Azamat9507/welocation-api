package com.minsu.cafe.order;

import com.minsu.cafe.TestcontainersConfig;
import com.minsu.cafe.cafe.Cafe;
import com.minsu.cafe.cafe.CafeRepository;
import com.minsu.cafe.menu.Menu;
import com.minsu.cafe.menu.MenuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OrderApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CafeRepository cafeRepository;

    @Autowired
    MenuRepository menuRepository;

    Cafe cafe;
    Menu latte;
    Menu cake;

    @BeforeEach
    void setUp() {
        cafe = cafeRepository.save(new Cafe("Minsu Coffee", "Seoul"));
        latte = menuRepository.save(new Menu(cafe, "Cafe Latte", 4_500));
        cake = menuRepository.save(new Menu(cafe, "Cheesecake", 6_000));
    }

    @Test
    void totalIsComputedFromDatabasePrices() throws Exception {
        String body = """
                {"cafeId": %d, "items": [
                  {"menuId": %d, "quantity": 2},
                  {"menuId": %d, "quantity": 1}
                ]}""".formatted(cafe.getId(), latte.getId(), cake.getId());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalPrice").value(15_000))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void menuFromAnotherCafeIsRejected() throws Exception {
        Cafe other = cafeRepository.save(new Cafe("Other", "Busan"));
        Menu foreign = menuRepository.save(new Menu(other, "Americano", 3_000));
        String body = """
                {"cafeId": %d, "items": [{"menuId": %d, "quantity": 1}]}"""
                .formatted(cafe.getId(), foreign.getId());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zeroQuantityFailsValidation() throws Exception {
        String body = """
                {"cafeId": %d, "items": [{"menuId": %d, "quantity": 0}]}"""
                .formatted(cafe.getId(), latte.getId());

        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
