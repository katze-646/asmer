package com.kayz.asmer.cache.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kayz.asmer.AsmerCache;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caffeine-backed {@link AsmerCache} with per-namespace isolation.
 *
 * <p>Each rule (namespace) gets its own Caffeine cache instance, lazily created.
 * Entries expire after the configured TTL from the time they were written.
 *
 * <p>Usage:
 * <pre>{@code
 * AsmerConfig config = AsmerConfig.builder()
 *     .cache(CaffeineCache.ttl(Duration.ofMinutes(5)))
 *     .build();
 *
 * // With custom max size:
 * AsmerConfig config = AsmerConfig.builder()
 *     .cache(CaffeineCache.ttl(Duration.ofMinutes(5), 50_000))
 *     .build();
 * }</pre>
 *
 * <p>Add the {@code asmer-cache-caffeine} module to your dependencies to use this class.
 */
public final class CaffeineCache implements AsmerCache {

    private static final long DEFAULT_MAX_SIZE = 10_000;

    private final Duration ttl;
    private final long maximumSize;
    private final ConcurrentHashMap<String, Cache<Object, Object>> namespaces = new ConcurrentHashMap<>();

    private CaffeineCache(Duration ttl, long maximumSize) {
        this.ttl         = Objects.requireNonNull(ttl, "ttl");
        this.maximumSize = maximumSize;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be > 0, got " + maximumSize);
        }
    }

    /**
     * Creates a cache with the given TTL and the default maximum size of 10,000 entries per namespace.
     */
    public static CaffeineCache ttl(Duration ttl) {
        return new CaffeineCache(ttl, DEFAULT_MAX_SIZE);
    }

    /**
     * Creates a cache with the given TTL and maximum size per namespace.
     */
    public static CaffeineCache ttl(Duration ttl, long maximumSize) {
        return new CaffeineCache(ttl, maximumSize);
    }

    // ---- AsmerCache per-key ops ----------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Optional<V> get(String namespace, K key) {
        Cache<Object, Object> cache = namespaces.get(namespace);
        if (cache == null) return Optional.empty();
        Object value = cache.getIfPresent(key);
        return Optional.ofNullable((V) value);
    }

    @Override
    public <K, V> void put(String namespace, K key, V value) {
        resolve(namespace).put(key, value);
    }

    // ---- AsmerCache batch ops (overridden for performance) --------------

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getAll(String namespace, Collection<K> keys) {
        Cache<Object, Object> cache = namespaces.get(namespace);
        if (cache == null) return Map.of();
        Map<K, V> result = new HashMap<>(keys.size());
        for (K key : keys) {
            Object value = cache.getIfPresent(key);
            if (value != null) result.put(key, (V) value);
        }
        return result;
    }

    @Override
    public <K, V> void putAll(String namespace, Map<K, V> entries) {
        if (entries.isEmpty()) return;
        Cache<Object, Object> cache = resolve(namespace);
        entries.forEach(cache::put);
    }

    // ---- eviction -------------------------------------------------------

    @Override
    public void evict(String namespace) {
        Cache<Object, Object> cache = namespaces.get(namespace);
        if (cache != null) cache.invalidateAll();
    }

    // ---- private --------------------------------------------------------

    private Cache<Object, Object> resolve(String namespace) {
        return namespaces.computeIfAbsent(namespace, ns ->
                Caffeine.newBuilder()
                        .expireAfterWrite(ttl)
                        .maximumSize(maximumSize)
                        .build());
    }

    @Override
    public String toString() {
        return "CaffeineCache{ttl=" + ttl + ", maxSize=" + maximumSize + "}";
    }
}
