package com.example.demo;

import com.example.demo.model.Order;
import com.example.demo.model.OrderItem;
import com.example.demo.model.User;
import com.example.demo.repository.OrderItemRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.kayz.asmer.Asmer;
import com.kayz.asmer.AsmerCache;
import com.kayz.asmer.AssemblyEvent;
import com.kayz.asmer.AssemblyListener;
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
 * ── Core ──────────────────────────────────────────────────────────────────
 * GET /orders              — Asmer.of(data), picks up YAML defaults automatically
 * GET /orders/{id}         — single-entity convenience factory Asmer.of(entity)
 * GET /orders/status/{s}   — AsmerJpa.byId() Spring Data helper
 *
 * ── Config ────────────────────────────────────────────────────────────────
 * GET /orders/inline       — fully inline fluent: .cache().concurrency().errorPolicy()
 * GET /orders/override     — YAML defaults as base, override one field per-call
 * GET /orders/timeout      — Concurrency.platformThreads().withTimeout(Duration)
 * GET /orders/onEach       — N+1 fallback via onEach (per-entity single fetch)
 *
 * ── Fetchers ──────────────────────────────────────────────────────────────
 * GET /orders/parallel     — Fetchers.parallel(): concurrent single-key → batch
 * GET /orders/fromMap      — Fetchers.fromMap(): Map-returning RPC/HTTP adapter
 *
 * ── Cache ─────────────────────────────────────────────────────────────────
 * GET /orders/chained      — AsmerCache.chain(l1, l2): two-level cache
 *
 * ── Observability (Sprint 3) ──────────────────────────────────────────────
 * GET /orders/listener     — AssemblyListener: returns per-rule metrics as JSON
 *
 * ── Custom Cache SPI (Sprint 4) ───────────────────────────────────────────
 * GET /orders/custom-cache — AsmerCache 自定义实现示例（ConcurrentHashMap），展示 cacheHits 变化
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

    /**
     * 自定义缓存：用 ConcurrentHashMap 实现 AsmerCache 接口。
     * 跨请求共享，第 2 次调用可观察到 cacheHits > 0。
     */
    private final AsmerCache customCache = mapCache("custom");

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

    // ---- 11. AssemblyListener — per-rule metrics (Sprint 3) -------------

    /**
     * Attaches an inline listener that collects {@link AssemblyEvent} per rule.
     * The response body IS the metrics — ruleName, keyCount, cacheHits, duration, success.
     *
     * <p>Call this endpoint twice in quick succession to see cacheHits > 0 on the second call
     * (the chained L1+L2 cache warms up after the first request).
     *
     * <p>In production, replace the inline listener with the auto-configured
     * {@code MicrometerAssemblyListener} (added automatically when Micrometer is on the classpath).
     */
    @GetMapping("/listener")
    public List<AssemblyEvent> withListener() {
        List<Order> orders = orderRepo.findAll();
        List<AssemblyEvent> events = new java.util.ArrayList<>();

        Asmer.of(orders)
                .cache(chainedCache)                  // warm cache → hits visible on 2nd call
                .listener(events::add)                // collect one event per rule
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();

        return events;
    }

    // ---- 12. Custom AsmerCache implementation (Sprint 4) ----------------

    /**
     * 展示如何用匿名内部类实现 {@link AsmerCache}，无需引入任何缓存依赖。
     *
     * <ul>
     *   <li>第 1 次调用：{@code cacheHits=0}，数据从 DB 加载后写入自定义缓存。</li>
     *   <li>第 2 次调用：{@code cacheHits>0}，命中自定义缓存，不再查 DB。</li>
     * </ul>
     *
     * <p>响应体为 {@link AssemblyEvent} 列表，包含 {@code ruleName}、{@code keyCount}、
     * {@code cacheHits}、{@code duration}、{@code success} 字段，便于直接观察缓存效果。
     */
    @GetMapping("/custom-cache")
    public List<AssemblyEvent> withCustomCache() {
        List<Order> orders = orderRepo.findAll();
        List<AssemblyEvent> events = new java.util.ArrayList<>();

        Asmer.of(orders)
                .cache(customCache)
                .listener(events::add)
                .on(Order::getUser,  userRepo::findByIdIn,      User::getId)
                .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
                .assemble();

        return events;
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
