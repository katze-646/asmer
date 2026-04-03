package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyListenerTest {

    // ---- fixtures --------------------------------------------------------

    static class Order {
        private final Long id;
        private final Long userId;

        @AssembleOne(keyField = "userId")
        private User user;

        @AssembleMany(keyField = "id")
        private List<Item> items;

        Order(Long id, Long userId) { this.id = id; this.userId = userId; }

        public Long getId()                    { return id; }
        public Long getUserId()                { return userId; }
        public User getUser()                  { return user; }
        public void setUser(User user)         { this.user = user; }
        public List<Item> getItems()           { return items; }
        public void setItems(List<Item> items) { this.items = items; }
    }

    static class User {
        private final Long id;
        User(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    static class Item {
        private final Long orderId;
        Item(Long orderId) { this.orderId = orderId; }
        public Long getOrderId() { return orderId; }
    }

    // ---- listener is called for each rule --------------------------------

    @Test
    void listener_calledOncePerRule() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(events::add)
                .on(Order::getUser,  ids -> List.of(new User(10L)),  User::getId)
                .on(Order::getItems, ids -> List.of(new Item(1L)),   Item::getOrderId)
                .assemble();

        assertEquals(2, events.size(), "one event per rule");
    }

    // ---- event fields ----------------------------------------------------

    @Test
    void event_ruleName_matchesFieldName() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        assertEquals("user", events.get(0).ruleName());
    }

    @Test
    void event_keyCount_reflectsDeduplicatedKeys() {
        List<AssemblyEvent> events = new ArrayList<>();
        // 3 orders, 2 share same userId → 2 unique keys
        List<Order> orders = List.of(
                new Order(1L, 10L),
                new Order(2L, 10L),
                new Order(3L, 20L)
        );

        Asmer.of(orders)
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L), new User(20L)), User::getId)
                .assemble();

        assertEquals(2, events.get(0).keyCount());
    }

    @Test
    void event_success_trueOnNormalExecution() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        assertTrue(events.get(0).success());
    }

    @Test
    void event_duration_isPositive() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        assertFalse(events.get(0).duration().isNegative());
        assertFalse(events.get(0).duration().isZero());
    }

    // ---- cache hits ------------------------------------------------------

    @Test
    void event_cacheHits_zeroOnFirstCall() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        assertEquals(0, events.get(0).cacheHits());
    }

    @Test
    void event_cacheHits_countedOnSubsequentCall() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        // Simple in-memory cache
        var store = new ConcurrentHashMap<String, Object>();
        AsmerCache cache = new AsmerCache() {
            @SuppressWarnings("unchecked")
            public <K, V> java.util.Optional<V> get(String ns, K key) {
                return Optional.ofNullable((V) store.get(ns + ":" + key));
            }
            public <K, V> void put(String ns, K key, V value) {
                store.put(ns + ":" + key, value);
            }
        };

        // First call — populates cache
        Asmer.of(List.of(order))
                .cache(cache)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        // Second call — should hit cache
        order.setUser(null); // reset
        Asmer.of(List.of(order))
                .cache(cache)
                .listener(events::add)
                .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                .assemble();

        assertEquals(1, events.get(0).cacheHits(), "second call must report 1 cache hit");
    }

    // ---- failure event ---------------------------------------------------

    @Test
    void event_success_falseWhenLoaderThrows() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        assertThrows(AssemblyException.class, () ->
                Asmer.of(List.of(order))
                        .listener(events::add)
                        .on(Order::getUser, ids -> { throw new RuntimeException("down"); }, User::getId)
                        .assemble()
        );

        assertEquals(1, events.size());
        assertFalse(events.get(0).success());
    }

    @Test
    void event_emittedEvenWithLogAndSkipPolicy() {
        List<AssemblyEvent> events = new ArrayList<>();
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                .listener(events::add)
                .on(Order::getUser, ids -> { throw new RuntimeException("down"); }, User::getId)
                .assemble();

        assertEquals(1, events.size());
        assertFalse(events.get(0).success());
    }

    // ---- no listener set — noop default ---------------------------------

    @Test
    void noListener_noException() {
        Order order = new Order(1L, 10L);

        assertDoesNotThrow(() ->
                Asmer.of(List.of(order))
                        .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                        .assemble()
        );
    }

    // ---- listener exception does not abort assembly ---------------------

    @Test
    void listenerException_doesNotAbortAssembly() {
        AtomicBoolean assembled = new AtomicBoolean(false);
        Order order = new Order(1L, 10L);

        Asmer.of(List.of(order))
                .listener(e -> { throw new RuntimeException("listener bug"); })
                .on(Order::getUser, ids -> {
                    assembled.set(true);
                    return List.of(new User(10L));
                }, User::getId)
                .assemble();

        assertTrue(assembled.get(), "assembly must complete even if listener throws");
        assertNotNull(order.getUser());
    }
}
