package com.example.demo.repository;

import com.example.demo.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);
}
