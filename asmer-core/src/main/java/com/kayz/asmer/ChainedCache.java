package com.kayz.asmer;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two-level cache: checks L1 first, falls back to L2 on a miss,
 * and promotes L2 hits into L1 for subsequent lookups.
 *
 * <p>On writes, entries are stored in both levels simultaneously.
 *
 * <p>Use {@link AsmerCache#chain(AsmerCache, AsmerCache)} or
 * {@link AsmerCache#andThen(AsmerCache)} to construct instances.
 */
final class ChainedCache implements AsmerCache {

    private final AsmerCache l1;
    private final AsmerCache l2;

    ChainedCache(AsmerCache l1, AsmerCache l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public <K, V> Optional<V> get(String namespace, K key) {
        Optional<V> hit = l1.get(namespace, key);
        if (hit.isPresent()) return hit;

        Optional<V> l2Hit = l2.get(namespace, key);
        l2Hit.ifPresent(v -> l1.put(namespace, key, v)); // promote to L1
        return l2Hit;
    }

    @Override
    public <K, V> void put(String namespace, K key, V value) {
        l1.put(namespace, key, value);
        l2.put(namespace, key, value);
    }

    @Override
    public <K, V> Map<K, V> getAll(String namespace, Collection<K> keys) {
        Map<K, V> l1Hits = l1.getAll(namespace, keys);

        List<K> missing = keys.stream().filter(k -> !l1Hits.containsKey(k)).toList();
        if (missing.isEmpty()) return l1Hits;

        Map<K, V> l2Hits = l2.getAll(namespace, missing);
        if (!l2Hits.isEmpty()) {
            l1.putAll(namespace, l2Hits); // promote L2 hits into L1
        }

        if (l1Hits.isEmpty()) return l2Hits;

        Map<K, V> combined = new HashMap<>(l1Hits.size() + l2Hits.size());
        combined.putAll(l1Hits);
        combined.putAll(l2Hits);
        return combined;
    }

    @Override
    public <K, V> void putAll(String namespace, Map<K, V> entries) {
        l1.putAll(namespace, entries);
        l2.putAll(namespace, entries);
    }

    @Override
    public void evict(String namespace) {
        l1.evict(namespace);
        l2.evict(namespace);
    }

    @Override
    public String toString() {
        return "ChainedCache{l1=" + l1 + ", l2=" + l2 + "}";
    }
}
