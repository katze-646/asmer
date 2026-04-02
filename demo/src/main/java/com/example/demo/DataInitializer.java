package com.example.demo;

import com.example.demo.model.Order;
import com.example.demo.model.OrderItem;
import com.example.demo.model.User;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepo;
    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;

    public DataInitializer(UserRepository userRepo,
                           OrderRepository orderRepo,
                           OrderItemRepository itemRepo) {
        this.userRepo  = userRepo;
        this.orderRepo = orderRepo;
        this.itemRepo  = itemRepo;
    }

    @Override
    public void run(String... args) {
        User alice = userRepo.save(new User("Alice", "alice@example.com"));
        User bob   = userRepo.save(new User("Bob",   "bob@example.com"));
        User carol = userRepo.save(new User("Carol", "carol@example.com"));

        Order o1 = orderRepo.save(new Order(alice.getId(), "PENDING"));
        Order o2 = orderRepo.save(new Order(alice.getId(), "SHIPPED"));
        Order o3 = orderRepo.save(new Order(bob.getId(),   "PENDING"));
        Order o4 = orderRepo.save(new Order(carol.getId(), "DELIVERED"));

        itemRepo.save(new OrderItem(o1.getId(), "Keyboard",  1, new BigDecimal("129.00")));
        itemRepo.save(new OrderItem(o1.getId(), "Mouse",     1, new BigDecimal("49.00")));
        itemRepo.save(new OrderItem(o2.getId(), "Monitor",   2, new BigDecimal("399.00")));
        itemRepo.save(new OrderItem(o3.getId(), "Headphones",1, new BigDecimal("89.00")));
        itemRepo.save(new OrderItem(o4.getId(), "Laptop",    1, new BigDecimal("1299.00")));
        itemRepo.save(new OrderItem(o4.getId(), "Charger",   1, new BigDecimal("59.00")));
        itemRepo.save(new OrderItem(o4.getId(), "Bag",       1, new BigDecimal("39.00")));

        log.info("Demo data initialized: {} users, {} orders, {} items",
                userRepo.count(), orderRepo.count(), itemRepo.count());
    }
}
