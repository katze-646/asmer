package com.kayz.asmer.spring;

import com.kayz.asmer.Asmer;
import com.kayz.asmer.AsmerCache;
import com.kayz.asmer.AsmerConfig;
import com.kayz.asmer.Concurrency;
import com.kayz.asmer.spring.model.OrderEntity;
import com.kayz.asmer.spring.model.OrderItemEntity;
import com.kayz.asmer.spring.model.UserEntity;
import com.kayz.asmer.spring.repo.OrderItemRepository;
import com.kayz.asmer.spring.repo.OrderRepository;
import com.kayz.asmer.spring.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AsmerJpaIntegrationTest {

    @SpringBootApplication
    static class TestApp {}

    @Autowired UserRepository userRepo;
    @Autowired OrderRepository orderRepo;
    @Autowired OrderItemRepository itemRepo;
    @Autowired AsmerConfig asmerConfig;

    @BeforeEach
    void setup() {
        itemRepo.deleteAll();
        orderRepo.deleteAll();
        userRepo.deleteAll();

        UserEntity alice = userRepo.save(new UserEntity("Alice"));
        UserEntity bob   = userRepo.save(new UserEntity("Bob"));

        OrderEntity order1 = orderRepo.save(new OrderEntity(alice.getId(), "PENDING"));
        OrderEntity order2 = orderRepo.save(new OrderEntity(alice.getId(), "PENDING"));
        OrderEntity order3 = orderRepo.save(new OrderEntity(bob.getId(),   "SHIPPED"));

        itemRepo.save(new OrderItemEntity(order1.getId(), "Keyboard", 1));
        itemRepo.save(new OrderItemEntity(order1.getId(), "Mouse",    2));
        itemRepo.save(new OrderItemEntity(order2.getId(), "Monitor",  1));
        itemRepo.save(new OrderItemEntity(order3.getId(), "Laptop",   1));
        itemRepo.save(new OrderItemEntity(order3.getId(), "Charger",  1));
    }

    @Test
    void autoConfigurationCreatesAsmerConfigBean() {
        assertNotNull(asmerConfig);
    }

    @Test
    void manyToOne_directRepoMethod() {
        List<OrderEntity> orders = orderRepo.findByStatus("PENDING");

        Asmer.of(orders)
                .on(OrderEntity::getUser, userRepo::findByIdIn, UserEntity::getId)
                .assemble();

        orders.forEach(o -> {
            assertNotNull(o.getUser());
            assertEquals("Alice", o.getUser().getName());
        });
    }

    @Test
    void manyToOne_usingAsmerJpaByIdHelper() {
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders)
                .on(OrderEntity::getUser, AsmerJpa.byId(userRepo), UserEntity::getId)
                .assemble();

        orders.forEach(o -> assertNotNull(o.getUser()));
        orders.forEach(System.out::println);
    }

    @Test
    void oneToMany_groupedByForeignKey() {
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders)
                .on(OrderEntity::getItems, itemRepo::findByOrderIdIn, OrderItemEntity::getOrderId)
                .assemble();

        orders.forEach(o -> {
            assertNotNull(o.getItems());
            assertFalse(o.getItems().isEmpty());
        });
        orders.forEach(System.out::println);
    }

    @Test
    void combined_bothRulesOnSameBatch() {
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders)
                .on(OrderEntity::getUser,  userRepo::findByIdIn, UserEntity::getId)
                .on(OrderEntity::getItems, itemRepo::findByOrderIdIn, OrderItemEntity::getOrderId)
                .assemble();

        orders.forEach(o -> {
            assertNotNull(o.getUser());
            assertNotNull(o.getItems());
            assertFalse(o.getItems().isEmpty());
        });
        orders.forEach(System.out::println);
    }

    @Test
    void batchDeduplication_sameUserIdLoadedOnce() {
        AtomicInteger queryCount = new AtomicInteger(0);

        // Alice owns 2 PENDING orders — userId is deduplicated to 1 loader call
        List<OrderEntity> orders = orderRepo.findByStatus("PENDING");
        assertEquals(2, orders.size());

        // Disable cache: this test verifies batch deduplication, not caching.
        // Without explicit cache(none), a warm global cache from a prior test would
        // return cache hits and the loader would never be called.
        Asmer.of(orders)
                .cache(AsmerCache.none())
                .on(OrderEntity::getUser,
                        ids -> { queryCount.incrementAndGet(); return userRepo.findByIdIn(ids); },
                        UserEntity::getId)
                .assemble();

        assertEquals(1, queryCount.get(), "Should invoke loader once despite 2 orders sharing userId");
        assertEquals(orders.get(0).getUser().getId(), orders.get(1).getUser().getId());
    }

    @Test
    void cache_secondAssembleSkipsLoader() {
        AtomicInteger queryCount = new AtomicInteger(0);

        // Use a simple in-memory cache to verify second assemble hits cache
        AsmerCache spyCache = new AsmerCache() {
            private final Map<String, Object> store = new ConcurrentHashMap<>();

            @Override
            public <K, V> Optional<V> get(String ns, K key) {
                @SuppressWarnings("unchecked")
                V v = (V) store.get(ns + ":" + key);
                return Optional.ofNullable(v);
            }

            @Override
            public <K, V> void put(String ns, K key, V value) {
                store.put(ns + ":" + key, value);
            }
        };

        AsmerConfig cfg = AsmerConfig.builder()
                .concurrency(Concurrency.callerThread())
                .cache(spyCache)
                .build();

        List<OrderEntity> firstBatch = orderRepo.findByStatus("PENDING");
        Asmer.of(firstBatch, cfg)
                .on(OrderEntity::getUser,
                        ids -> { queryCount.incrementAndGet(); return userRepo.findByIdIn(ids); },
                        UserEntity::getId)
                .assemble();
        assertEquals(1, queryCount.get());

        // reset assembled fields, then reassemble — should be fully cached
        firstBatch.forEach(o -> o.setUser(null));
        Asmer.of(firstBatch, cfg)
                .on(OrderEntity::getUser,
                        ids -> { queryCount.incrementAndGet(); return userRepo.findByIdIn(ids); },
                        UserEntity::getId)
                .assemble();
        assertEquals(1, queryCount.get(), "Second assemble should be served from cache");
        firstBatch.forEach(o -> assertNotNull(o.getUser()));
    }

    @Test
    void platformThreads_concurrentRulesProduceCorrectResults() {
        AsmerConfig cfg = AsmerConfig.builder()
                .concurrency(Concurrency.platformThreads())
                .build();

        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders, cfg)
                .on(OrderEntity::getUser,  userRepo::findByIdIn, UserEntity::getId)
                .on(OrderEntity::getItems, itemRepo::findByOrderIdIn, OrderItemEntity::getOrderId)
                .assemble();

        orders.forEach(o -> {
            assertNotNull(o.getUser());
            assertFalse(o.getItems().isEmpty());
        });
    }

    @Test
    void shippedOrder_correctUserAndItemCount() {
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders)
                .on(OrderEntity::getUser,  userRepo::findByIdIn, UserEntity::getId)
                .on(OrderEntity::getItems, itemRepo::findByOrderIdIn, OrderItemEntity::getOrderId)
                .assemble();

        OrderEntity shipped = orders.stream()
                .filter(o -> "SHIPPED".equals(o.getStatus()))
                .findFirst().orElseThrow();

        assertEquals("Bob", shipped.getUser().getName());
        assertEquals(2, shipped.getItems().size());
    }

    @Test
    void onEach_fallbackWithoutBatchApi() {
        // onEach wraps K->V; each key is fetched individually (N+1 fallback)
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders)
                .onEach(OrderEntity::getUser, (Long id) -> userRepo.findById(id).orElse(null))
                .assemble();

        orders.forEach(o -> assertNotNull(o.getUser()));
    }

    @Test
    void autoConfigBean_usedDirectly() {
        List<OrderEntity> orders = orderRepo.findAll();

        Asmer.of(orders, asmerConfig)
                .on(OrderEntity::getUser,  userRepo::findByIdIn, UserEntity::getId)
                .on(OrderEntity::getItems, itemRepo::findByOrderIdIn, OrderItemEntity::getOrderId)
                .assemble();

        orders.forEach(o -> {
            assertNotNull(o.getUser());
            assertNotNull(o.getItems());
        });
    }
}
