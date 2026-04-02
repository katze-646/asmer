package com.example.demo;

import com.example.demo.model.Order;
import com.example.demo.model.OrderItem;
import com.example.demo.model.User;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.kayz.asmer.Asmer;
import com.kayz.asmer.AsmerCache;
import com.kayz.asmer.Concurrency;
import com.kayz.asmer.ErrorPolicy;
import com.kayz.asmer.Fetchers;
import com.kayz.asmer.spring.AsmerJpa;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demonstrates Asmer usage patterns.
 *
 * GET /orders              — bare Asmer.of(data), uses YAML defaults automatically
 * GET /orders/{id}         — single entity
 * GET /orders/status/{s}   — AsmerJpa.byId() helper
 * GET /orders/inline       — fully inline fluent config (no injection needed)
 * GET /orders/override     — YAML defaults as base, override one setting per-call
 * GET /orders/timeout      — concurrency with timeout
 * GET /orders/onEach       — N+1 fallback
 * GET /orders/parallel     — Fetchers.parallel() for concurrent single-key fetch
 * GET /orders/fromMap      — Fetchers.fromMap() for map-returning RPC/HTTP
 * GET /orders/chained      — L1+L2 chained cache
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final UserRepository userRepo;

    private final AsmerCache l1Cache = mapCache("L1");
    private final AsmerCache l2Cache = mapCache("L2");
    private final AsmerCache chainedCache = l1Cache.andThen(l2Cache);

    public OrderController(OrderRepository orderRepo,
                           OrderItemRepository itemRepo,
                           UserRepository userRepo) {
        this.orderRepo = orderRepo;
        this.itemRepo  = itemRepo;
        this.userRepo  = userRepo;
    }

    // ---- 1. Bare Asmer.of — uses YAML defaults automatically ---------------

    /**
     * No AsmerConfig injection needed.
     * AsmerAutoConfiguration sets the global default at startup, so
     * Asmer.of(data) picks up application.yaml settings (cache, concurrency, errorPolicy).
     *
     * SQL: 1 orders + 1 users IN (...) + 1 order_items IN (...) = 3 total.
     */
    @GetMapping
    public List<Order> listAll() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 2. Single entity ------------------------------------------------

    @GetMapping("/{id}")
    public Order getOne(@PathVariable Long id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found: " + id));
        Asmer.of(order)
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return order;
    }

    // ---- 3. AsmerJpa helper ----------------------------------------------

    @GetMapping("/status/{status}")
    public List<Order> byStatus(@PathVariable String status) {
        List<Order> orders = orderRepo.findByStatus(status);
        Asmer.of(orders)
                .on(Order::getUser,  AsmerJpa.byId(userRepo),   User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 4. Fully inline fluent config (no AsmerConfig object needed) ----

    /**
     * All config set directly on the Asmer instance — no AsmerConfig needed.
     * This is the cleanest form for one-off customization.
     */
    @GetMapping("/inline")
    public List<Order> withInlineConfig() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .cache(chainedCache)
                .concurrency(Concurrency.platformThreads(2))
                .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 5. YAML defaults as base, override a single setting per-call ----

    /**
     * Starts from the YAML global default (concurrency + errorPolicy from yaml),
     * then overrides just the cache for this specific endpoint.
     */
    @GetMapping("/override")
    public List<Order> withCacheOverride() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .cache(l1Cache)   // override only the cache; concurrency + errorPolicy from yaml
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 6. Concurrency with timeout ------------------------------------

    @GetMapping("/timeout")
    public List<Order> withTimeout() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .concurrency(Concurrency.platformThreads()
                        .withTimeout(Duration.ofSeconds(5)))
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 7. N+1 fallback via onEach -------------------------------------

    @GetMapping("/onEach")
    public List<Order> withOnEach() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .onEach(Order::getUser,  (Long id)  -> userRepo.findById(id).orElse(null))
                .onEach(Order::getItems, (Long oid) -> itemRepo.findByOrderIdIn(List.of(oid)))
                .assemble();
        return orders;
    }

    // ---- 8. Fetchers.parallel(): concurrent single-key fetch ------------

    @GetMapping("/parallel")
    public List<Order> withParallelFetcher() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .on(Order::getUser,
                        Fetchers.parallel((Long id) -> userRepo.findById(id).orElse(null)),
                        User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 9. Fetchers.fromMap(): map-returning RPC/HTTP adapter ----------

    @GetMapping("/fromMap")
    public List<Order> withFromMapFetcher() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .on(Order::getUser,
                        Fetchers.fromMap(ids -> {
                            // Simulate RPC that returns Map<Long, User>
                            Map<Long, User> result = new ConcurrentHashMap<>();
                            userRepo.findByIdIn(ids).forEach(u -> result.put(u.getId(), u));
                            return result;
                        }),
                        User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- 10. L1+L2 chained cache (inline + fluent) ----------------------

    /**
     * Combines inline cache config with chained L1+L2.
     * First request: L1 miss → L2 miss → DB → writes both levels.
     * Second request: L1 hit → no DB, no L2 call.
     *
     * Real-world: l1Cache = CaffeineCache.ttl(...), l2Cache = RedisCache.of(...)
     */
    @GetMapping("/chained")
    public List<Order> withChainedCache() {
        List<Order> orders = orderRepo.findAll();
        Asmer.of(orders)
                .cache(chainedCache)
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();
        return orders;
    }

    // ---- helper ---------------------------------------------------------

    private static AsmerCache mapCache(String label) {
        Map<String, Object> store = new ConcurrentHashMap<>();
        return new AsmerCache() {
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

            @Override
            public String toString() { return label + "{size=" + store.size() + "}"; }
        };
    }
}
