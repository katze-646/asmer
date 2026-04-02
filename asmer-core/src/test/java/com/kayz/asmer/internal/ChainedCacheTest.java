package com.kayz.asmer.internal;

import com.kayz.asmer.AsmerCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ChainedCacheTest {

    // ---- simple in-memory spy cache ----------------------------------------

    static class MapCache implements AsmerCache {
        final Map<String, Object> store = new ConcurrentHashMap<>();
        int getCount = 0;
        int putCount = 0;

        String key(String ns, Object k) { return ns + ":" + k; }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> Optional<V> get(String ns, K key) {
            getCount++;
            return Optional.ofNullable((V) store.get(key(ns, key)));
        }

        @Override
        public <K, V> void put(String ns, K key, V value) {
            putCount++;
            store.put(key(ns, key), value);
        }

        @Override
        public void evict(String ns) {
            store.keySet().removeIf(k -> k.startsWith(ns + ":"));
        }
    }

    static final String NS = "users";

    MapCache l1;
    MapCache l2;
    AsmerCache chained;

    @BeforeEach
    void setUp() {
        l1 = new MapCache();
        l2 = new MapCache();
        chained = AsmerCache.chain(l1, l2);
    }

    // ---- get ----------------------------------------------------------------

    @Nested
    class Get {

        @Test
        void l1Hit_returnsL1_doesNotQueryL2() {
            l1.store.put(l1.key(NS, 1L), "Alice");

            Optional<String> result = chained.get(NS, 1L);

            assertTrue(result.isPresent());
            assertEquals("Alice", result.get());
            assertEquals(0, l2.getCount, "L2 must not be queried on L1 hit");
        }

        @Test
        void l1Miss_l2Hit_returnsL2_promotesToL1() {
            l2.store.put(l2.key(NS, 2L), "Bob");

            Optional<String> result = chained.get(NS, 2L);

            assertTrue(result.isPresent());
            assertEquals("Bob", result.get());
            // promoted into L1
            assertTrue(l1.store.containsKey(l1.key(NS, 2L)),
                    "L2 hit must be promoted into L1");
        }

        @Test
        void bothMiss_returnsEmpty() {
            Optional<String> result = chained.get(NS, 99L);
            assertFalse(result.isPresent());
        }

        @Test
        void afterPromotion_subsequentGetHitsL1() {
            l2.store.put(l2.key(NS, 3L), "Carol");
            chained.get(NS, 3L); // triggers promotion
            l2.store.clear();    // wipe L2 to prove L1 is used next time

            int l2CountBefore = l2.getCount;
            Optional<String> result = chained.get(NS, 3L);

            assertTrue(result.isPresent());
            assertEquals("Carol", result.get());
            assertEquals(l2CountBefore, l2.getCount, "L2 must not be queried after promotion");
        }
    }

    // ---- put ----------------------------------------------------------------

    @Nested
    class Put {

        @Test
        void writesToBothLevels() {
            chained.put(NS, 1L, "Alice");

            assertTrue(l1.store.containsKey(l1.key(NS, 1L)));
            assertTrue(l2.store.containsKey(l2.key(NS, 1L)));
        }

        @Test
        void bothLevelsStoreCorrectValue() {
            chained.put(NS, 2L, "Bob");

            assertEquals("Bob", l1.store.get(l1.key(NS, 2L)));
            assertEquals("Bob", l2.store.get(l2.key(NS, 2L)));
        }
    }

    // ---- getAll -------------------------------------------------------------

    @Nested
    class GetAll {

        @Test
        void allL1Hits_doesNotQueryL2() {
            l1.store.put(l1.key(NS, 1L), "Alice");
            l1.store.put(l1.key(NS, 2L), "Bob");

            Map<Long, String> result = chained.getAll(NS, java.util.List.of(1L, 2L));

            assertEquals(2, result.size());
            assertEquals(0, l2.getCount, "L2 must not be queried when all keys hit L1");
        }

        @Test
        void allL2Hits_promotesAllToL1() {
            l2.store.put(l2.key(NS, 1L), "Alice");
            l2.store.put(l2.key(NS, 2L), "Bob");

            Map<Long, String> result = chained.getAll(NS, java.util.List.of(1L, 2L));

            assertEquals(2, result.size());
            assertTrue(l1.store.containsKey(l1.key(NS, 1L)), "key 1 must be promoted");
            assertTrue(l1.store.containsKey(l1.key(NS, 2L)), "key 2 must be promoted");
        }

        @Test
        void partialHits_combinesL1AndL2_promotesL2HitsToL1() {
            l1.store.put(l1.key(NS, 1L), "Alice");
            l2.store.put(l2.key(NS, 2L), "Bob");

            Map<Long, String> result = chained.getAll(NS, java.util.List.of(1L, 2L));

            assertEquals(2, result.size());
            assertEquals("Alice", result.get(1L));
            assertEquals("Bob",   result.get(2L));
            assertTrue(l1.store.containsKey(l1.key(NS, 2L)), "L2 hit must be promoted into L1");
        }

        @Test
        void allMiss_returnsEmptyMap() {
            Map<Long, String> result = chained.getAll(NS, java.util.List.of(10L, 20L));
            assertTrue(result.isEmpty());
        }
    }

    // ---- putAll -------------------------------------------------------------

    @Nested
    class PutAll {

        @Test
        void writesToBothLevels() {
            Map<Long, String> entries = Map.of(1L, "Alice", 2L, "Bob");
            chained.putAll(NS, entries);

            assertTrue(l1.store.containsKey(l1.key(NS, 1L)));
            assertTrue(l1.store.containsKey(l1.key(NS, 2L)));
            assertTrue(l2.store.containsKey(l2.key(NS, 1L)));
            assertTrue(l2.store.containsKey(l2.key(NS, 2L)));
        }
    }

    // ---- evict --------------------------------------------------------------

    @Nested
    class Evict {

        @Test
        void clearsBothLevels() {
            l1.store.put(l1.key(NS, 1L), "Alice");
            l2.store.put(l2.key(NS, 1L), "Alice");

            chained.evict(NS);

            // default evict clears by namespace prefix — our MapCache stores raw keys,
            // so just verify that the delegate evict methods were invoked by querying after
            assertFalse(chained.get(NS, 1L).isPresent());
        }
    }

    // ---- factory methods ----------------------------------------------------

    @Nested
    class Factory {

        @Test
        void chain_returnsChainedCacheInstance() {
            AsmerCache c = AsmerCache.chain(l1, l2);
            assertInstanceOf(ChainedCache.class, c);
        }

        @Test
        void andThen_returnsSameAsChain() {
            AsmerCache via_chain  = AsmerCache.chain(l1, l2);
            AsmerCache via_andThen = l1.andThen(l2);
            // both are ChainedCache; verify they behave identically
            assertInstanceOf(ChainedCache.class, via_chain);
            assertInstanceOf(ChainedCache.class, via_andThen);
        }

        @Test
        void chain_nullL1_throws() {
            assertThrows(NullPointerException.class, () -> AsmerCache.chain(null, l2));
        }

        @Test
        void chain_nullL2_throws() {
            assertThrows(NullPointerException.class, () -> AsmerCache.chain(l1, null));
        }
    }
}
