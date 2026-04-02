package com.kayz.asmer;

import com.kayz.asmer.internal.ChainedCache;
import com.kayz.asmer.internal.NoOpAsmerCache;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for plugging a cache into the assembly pipeline.
 *
 * <p>Implement {@link #get} and {@link #put} for a simple custom cache.
 * Override {@link #getAll} and {@link #putAll} for better performance when
 * talking to a remote store (e.g. Redis {@code MGET}).
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link #none()} — no-op, always a miss (default)</li>
 *   <li>{@code CaffeineCache} in {@code asmer-cache-caffeine} module</li>
 *   <li>{@code RedisCache} in {@code asmer-cache-redis} module</li>
 * </ul>
 *
 * <p>Usage example — custom in-memory cache backed by a {@code ConcurrentHashMap}:
 * <pre>{@code
 * AsmerCache myCache = new AsmerCache() {
 *     private final Map<String, Object> store = new ConcurrentHashMap<>();
 *
 *     public <K, V> Optional<V> get(String ns, K key) {
 *         return Optional.ofNullable((V) store.get(ns + ":" + key));
 *     }
 *     public <K, V> void put(String ns, K key, V value) {
 *         store.put(ns + ":" + key, value);
 *     }
 * };
 * }</pre>
 */
public interface AsmerCache {

    /**
     * Look up a single entry.
     *
     * @param namespace the assembly rule name (field name), used to isolate entries
     * @param key       the entity key
     * @return {@code Optional.empty()} on a cache miss, otherwise the cached value
     */
    <K, V> Optional<V> get(String namespace, K key);

    /**
     * Store a single entry.
     *
     * @param namespace the assembly rule name
     * @param key       the entity key
     * @param value     the value to cache (never {@code null})
     */
    <K, V> void put(String namespace, K key, V value);

    /**
     * Batch lookup. Override for better performance with remote caches.
     * The default delegates to {@link #get} for each key.
     *
     * @return a map of key → value for all cache hits; misses are absent
     */
    default <K, V> Map<K, V> getAll(String namespace, Collection<K> keys) {
        Map<K, V> result = new LinkedHashMap<>();
        for (K key : keys) {
            this.<K, V>get(namespace, key).ifPresent(v -> result.put(key, v));
        }
        return result;
    }

    /**
     * Batch store. Override for better performance with remote caches.
     * The default delegates to {@link #put} for each entry.
     */
    default <K, V> void putAll(String namespace, Map<K, V> entries) {
        entries.forEach((k, v) -> put(namespace, k, v));
    }

    /**
     * Evict all entries under the given namespace. Default is a no-op.
     */
    default void evict(String namespace) {}

    /**
     * Returns a two-level cache that checks {@code l1} first, then {@code l2} on a miss.
     * L2 hits are promoted into L1 for subsequent lookups.
     * Writes go to both levels simultaneously.
     *
     * <p>Typical use — local Caffeine as L1, Redis as L2:
     * <pre>{@code
     * AsmerCache tiered = AsmerCache.chain(
     *     CaffeineCache.ttl(Duration.ofMinutes(1)),   // L1: fast, short TTL
     *     RedisCache.of(template, Duration.ofHours(1)) // L2: shared, long TTL
     * );
     * }</pre>
     */
    static AsmerCache chain(AsmerCache l1, AsmerCache l2) {
        return new ChainedCache(
                java.util.Objects.requireNonNull(l1, "l1"),
                java.util.Objects.requireNonNull(l2, "l2"));
    }

    /**
     * Returns a two-level cache composed of {@code this} (L1) and {@code next} (L2).
     *
     * <p>Fluent alternative to {@link #chain}:
     * <pre>{@code
     * CaffeineCache.ttl(Duration.ofMinutes(1))
     *     .andThen(RedisCache.of(template, Duration.ofHours(1)))
     * }</pre>
     */
    default AsmerCache andThen(AsmerCache next) {
        return chain(this, next);
    }

    /**
     * Returns a no-op cache that never stores anything and always reports a miss.
     * This is the default when no cache is configured.
     */
    static AsmerCache none() {
        return NoOpAsmerCache.INSTANCE;
    }
}
