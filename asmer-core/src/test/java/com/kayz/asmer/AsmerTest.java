package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsmerTest {

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
        private final String name;

        User(Long id, String name) { this.id = id; this.name = name; }

        public Long getId()     { return id; }
        public String getName() { return name; }
    }

    static class Item {
        private final Long orderId;
        private final String name;

        Item(Long orderId, String name) { this.orderId = orderId; this.name = name; }

        public Long getOrderId() { return orderId; }
        public String getName()  { return name; }
    }

    /** No annotation on any field — for negative tests. */
    static class Bare {
        private String tag;

        Bare(String tag)               { this.tag = tag; }
        public String getTag()         { return tag; }
        public void setTag(String t)   { this.tag = t; }
    }

    // ---- @AssembleOne (many-to-one) -------------------------------------

    @Nested
    class AssembleOneTests {

        @Test
        void batchFetcher_setsValueOnAllEntities() {
            User alice = new User(10L, "Alice");
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 10L);

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser, ids -> List.of(alice), User::getId)
                    .assemble();

            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Alice", o2.getUser().getName());
        }

        @Test
        void differentKeys_eachEntityGetsOwnValue() {
            User alice = new User(10L, "Alice");
            User bob   = new User(20L, "Bob");
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 20L);

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser, ids -> List.of(alice, bob), User::getId)
                    .assemble();

            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Bob",   o2.getUser().getName());
        }

        @Test
        void nullKey_leavesFieldNull() {
            Order order = new Order(1L, null); // userId is null

            Asmer.of(List.of(order))
                    .on(Order::getUser, ids -> List.of(new User(99L, "Ghost")), User::getId)
                    .assemble();

            assertNull(order.getUser());
        }

        @Test
        void missingLoaderResult_leavesFieldNull() {
            Order order = new Order(1L, 999L); // no user with id 999

            Asmer.of(List.of(order))
                    .on(Order::getUser, ids -> List.of(), User::getId)
                    .assemble();

            assertNull(order.getUser());
        }

        @Test
        void batchDeduplication_loaderCalledOnceForSameKey() {
            AtomicInteger callCount = new AtomicInteger(0);
            User alice = new User(10L, "Alice");
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 10L); // same userId

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser, ids -> {
                        callCount.incrementAndGet();
                        return List.of(alice);
                    }, User::getId)
                    .assemble();

            assertEquals(1, callCount.get(), "loader must be called once despite two entities sharing the same key");
            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Alice", o2.getUser().getName());
        }

        @Test
        void onEach_n1Fallback_setsValueOnAllEntities() {
            User alice = new User(10L, "Alice");
            AtomicInteger callCount = new AtomicInteger(0);
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 10L); // same userId - called once

            Asmer.of(List.of(o1, o2))
                    .onEach(Order::getUser, userId -> {
                        callCount.incrementAndGet();
                        return alice;
                    })
                    .assemble();

            assertEquals(1, callCount.get(), "onEach must deduplicate keys");
            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Alice", o2.getUser().getName());
        }
    }

    // ---- @AssembleMany (one-to-many) ------------------------------------

    @Nested
    class AssembleManyTests {

        @Test
        void batchFetcher_setsListOnEntity() {
            Order order = new Order(1L, 10L);
            Item keyboard = new Item(1L, "Keyboard");
            Item mouse    = new Item(1L, "Mouse");

            Asmer.of(List.of(order))
                    .on(Order::getItems, ids -> List.of(keyboard, mouse), Item::getOrderId)
                    .assemble();

            assertEquals(2, order.getItems().size());
        }

        @Test
        void noChildren_setsEmptyList() {
            Order order = new Order(1L, 10L);

            Asmer.of(List.of(order))
                    .on(Order::getItems, ids -> List.of(), Item::getOrderId)
                    .assemble();

            assertNotNull(order.getItems());
            assertTrue(order.getItems().isEmpty());
        }

        @Test
        void nullKey_setsEmptyList() {
            Order order = new Order(null, 10L); // id is null

            Asmer.of(List.of(order))
                    .on(Order::getItems, ids -> List.of(new Item(null, "x")), Item::getOrderId)
                    .assemble();

            assertNotNull(order.getItems());
            assertTrue(order.getItems().isEmpty());
        }

        @Test
        void multipleOrders_eachGetsOwnItems() {
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 20L);
            Item i1 = new Item(1L, "Keyboard");
            Item i2 = new Item(2L, "Monitor");

            Asmer.of(List.of(o1, o2))
                    .on(Order::getItems, ids -> List.of(i1, i2), Item::getOrderId)
                    .assemble();

            assertEquals(1, o1.getItems().size());
            assertEquals("Keyboard", o1.getItems().get(0).getName());
            assertEquals(1, o2.getItems().size());
            assertEquals("Monitor", o2.getItems().get(0).getName());
        }

        @Test
        void onEach_n1Fallback_setsListOnEntity() {
            Order order = new Order(1L, 10L);
            List<Item> items = List.of(new Item(1L, "Keyboard"), new Item(1L, "Mouse"));
            AtomicInteger callCount = new AtomicInteger(0);

            Asmer.of(List.of(order))
                    .onEach(Order::getItems, orderId -> {
                        callCount.incrementAndGet();
                        return items;
                    })
                    .assemble();

            assertEquals(1, callCount.get());
            assertEquals(2, order.getItems().size());
        }
    }

    // ---- Combined rules -------------------------------------------------

    @Nested
    class CombinedRules {

        @Test
        void bothRulesExecuteOnSameEntity() {
            Order order = new Order(1L, 10L);
            User alice   = new User(10L, "Alice");
            Item keyboard = new Item(1L, "Keyboard");

            Asmer.of(List.of(order))
                    .on(Order::getUser,  ids -> List.of(alice),    User::getId)
                    .on(Order::getItems, ids -> List.of(keyboard), Item::getOrderId)
                    .assemble();

            assertEquals("Alice", order.getUser().getName());
            assertEquals(1, order.getItems().size());
        }

        @Test
        void multipleEntitiesBothRules() {
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 20L);
            User alice = new User(10L, "Alice");
            User bob   = new User(20L, "Bob");
            Item i1 = new Item(1L, "A");
            Item i2 = new Item(2L, "B");

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser,  ids -> List.of(alice, bob), User::getId)
                    .on(Order::getItems, ids -> List.of(i1, i2),     Item::getOrderId)
                    .assemble();

            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Bob",   o2.getUser().getName());
            assertEquals(1, o1.getItems().size());
            assertEquals(1, o2.getItems().size());
        }
    }

    // ---- Configuration --------------------------------------------------

    @Nested
    class ConfigTests {

        @Test
        void platformThreads_rulesExecuteAndProduceCorrectResults() {
            Order order = new Order(1L, 10L);
            User alice   = new User(10L, "Alice");
            Item keyboard = new Item(1L, "Keyboard");
            Set<String> threadNames = ConcurrentHashMap.newKeySet();

            AsmerConfig config = AsmerConfig.builder()
                    .concurrency(Concurrency.platformThreads(2))
                    .build();

            Asmer.of(List.of(order), config)
                    .on(Order::getUser,  ids -> { threadNames.add(Thread.currentThread().getName()); return List.of(alice); },    User::getId)
                    .on(Order::getItems, ids -> { threadNames.add(Thread.currentThread().getName()); return List.of(keyboard); }, Item::getOrderId)
                    .assemble();

            assertEquals("Alice", order.getUser().getName());
            assertEquals(1, order.getItems().size());
        }

        @Test
        void customCache_getAndPutAreCalled() {
            AtomicInteger putCount = new AtomicInteger(0);
            AtomicInteger getCount = new AtomicInteger(0);

            AsmerCache spyCache = new AsmerCache() {
                @Override
                public <K, V> Optional<V> get(String ns, K key) {
                    getCount.incrementAndGet();
                    return Optional.empty(); // always miss
                }

                @Override
                public <K, V> void put(String ns, K key, V value) {
                    putCount.incrementAndGet();
                }
            };

            AsmerConfig config = AsmerConfig.builder().cache(spyCache).build();
            Order order = new Order(1L, 10L);

            Asmer.of(List.of(order), config)
                    .on(Order::getUser, ids -> List.of(new User(10L, "Alice")), User::getId)
                    .assemble();

            assertEquals(1, getCount.get(), "cache.get called once for the one unique key");
            assertEquals(1, putCount.get(), "cache.put called once after loading");
        }

        @Test
        void customCache_hitSkipsLoader() {
            User cached = new User(10L, "FromCache");
            AtomicInteger loaderCallCount = new AtomicInteger(0);

            AsmerCache hitCache = new AsmerCache() {
                @SuppressWarnings("unchecked")
                @Override
                public <K, V> Optional<V> get(String ns, K key) {
                    return Optional.of((V) cached); // always hit
                }

                @Override
                public <K, V> void put(String ns, K key, V value) {}
            };

            AsmerConfig config = AsmerConfig.builder().cache(hitCache).build();
            Order order = new Order(1L, 10L);

            Asmer.of(List.of(order), config)
                    .on(Order::getUser, ids -> {
                        loaderCallCount.incrementAndGet();
                        return List.of(new User(10L, "FromLoader"));
                    }, User::getId)
                    .assemble();

            assertEquals(0, loaderCallCount.get(), "loader must not be called when cache hits");
            assertEquals("FromCache", order.getUser().getName());
        }

        @Test
        void errorPolicy_logAndSkip_loaderExceptionLeavesFieldNull() {
            AsmerConfig config = AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                    .build();
            Order order = new Order(1L, 10L);

            assertDoesNotThrow(() ->
                    Asmer.of(List.of(order), config)
                            .on(Order::getUser, ids -> { throw new RuntimeException("DB down"); }, User::getId)
                            .assemble()
            );
            assertNull(order.getUser());
        }

        @Test
        void errorPolicy_fallbackEmpty_manyFieldGetsEmptyList() {
            AsmerConfig config = AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.FALLBACK_EMPTY)
                    .build();
            Order order = new Order(1L, 10L);

            assertDoesNotThrow(() ->
                    Asmer.of(List.of(order), config)
                            .on(Order::getItems, ids -> { throw new RuntimeException("DB down"); }, Item::getOrderId)
                            .assemble()
            );
            assertNotNull(order.getItems());
            assertTrue(order.getItems().isEmpty());
        }

        @Test
        void errorPolicy_throw_loaderExceptionPropagates() {
            Order order = new Order(1L, 10L);

            assertThrows(AssemblyException.class, () ->
                    Asmer.of(List.of(order))
                            .on(Order::getUser, ids -> { throw new RuntimeException("DB down"); }, User::getId)
                            .assemble()
            );
        }
    }

    // ---- Edge cases -----------------------------------------------------

    @Nested
    class EdgeCases {

        @Test
        void emptyDataListWithExplicitType_noopNoException() {
            assertDoesNotThrow(() ->
                    Asmer.of(Order.class, List.of())
                            .on(Order::getUser, ids -> List.of(), User::getId)
                            .assemble()
            );
        }

        @Test
        void emptyDataList_onMethodStillValidatesAnnotation() {
            // @AssembleOne is present on Order.user, so on() must NOT throw even with empty data
            assertDoesNotThrow(() ->
                    Asmer.of(Order.class, List.of())
                            .on(Order::getUser, ids -> List.of(), User::getId)
                            .assemble()
            );
        }

        @Test
        void missingAnnotation_throwsRuleDefinitionExceptionAtOnCall() {
            Bare entity = new Bare("x");

            assertThrows(RuleDefinitionException.class, () ->
                    Asmer.of(List.of(entity))
                            .on(Bare::getTag, ids -> List.of(), s -> s)
            );
        }

        @Test
        void loaderReturnsNull_treatedAsEmpty() {
            Order order = new Order(1L, 10L);

            assertDoesNotThrow(() ->
                    Asmer.of(List.of(order))
                            .on(Order::getItems, ids -> null, Item::getOrderId)
                            .assemble()
            );
            // either null or empty list - just no NPE
        }

        @Test
        void singleEntity_worksCorrectly() {
            Order order = new Order(1L, 10L);

            Asmer.of(order) // single object convenience factory
                    .on(Order::getUser, ids -> List.of(new User(10L, "Alice")), User::getId)
                    .assemble();

            assertEquals("Alice", order.getUser().getName());
        }
    }
}
