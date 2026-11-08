package com.minsu.cafe.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<CafeOrder, Long> {
}
