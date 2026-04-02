package com.kayz.asmer.spring.repo;

import com.kayz.asmer.spring.model.OrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    List<OrderItemEntity> findByOrderIdIn(Collection<Long> orderIds);
}
