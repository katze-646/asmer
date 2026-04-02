package com.kayz.asmer.cache.redis;

import com.kayz.asmer.AsmerCache;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Redis-backed {@link AsmerCache} using Spring Data Redis.
 *
 * <p>Key format: {@code asmer:{namespace}:{key}}
 *
 * <p>Overrides {@link #getAll} to use Redis {@code MGET} (single round-trip for all keys)
 * and {@link #putAll} to issue individual {@code SET} commands with TTL.
 *
 * <p>Usage with Spring Boot auto-configuration:
 * <pre>{@code
 * // In your Spring configuration:
 * @Bean
 * AsmerCache asmerCache(RedisTemplate<String, Object> redisTemplate) {
 *     return RedisCache.of(redisTemplate, Duration.ofMinutes(10));
 * }
 * }</pre>
 */
public final class RedisCache implements AsmerCache {

    private static final String KEY_PREFIX = "asmer:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Duration ttl;

    private RedisCache(RedisTemplate<String, Object> redisTemplate, Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.ttl           = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
    }

    /** Creates a {@code RedisCache} with the given template and entry TTL. */
    public static RedisCache of(RedisTemplate<String, Object> redisTemplate, Duration ttl) {
        return new RedisCache(redisTemplate, ttl);
    }

    // ---- per-key ops ----------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Optional<V> get(String namespace, K key) {
        Object value = redisTemplate.opsForValue().get(buildKey(namespace, key));
        return Optional.ofNullable((V) value);
    }

    @Override
    public <K, V> void put(String namespace, K key, V value) {
        redisTemplate.opsForValue().set(buildKey(namespace, key), value, ttl);
    }

    // ---- batch ops (override for MGET efficiency) -----------------------

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Map<K, V> getAll(String namespace, Collection<K> keys) {
        List<String> redisKeys = keys.stream().map(k -> buildKey(namespace, k)).toList();
        List<Object> values    = redisTemplate.opsForValue().multiGet(redisKeys);

        if (values == null) return Map.of();

        Map<K, V> result = new HashMap<>(keys.size());
        var keyIter = keys.iterator();
        for (Object value : values) {
            K key = keyIter.next();
            if (value != null) result.put(key, (V) value);
        }
        return result;
    }

    @Override
    public <K, V> void putAll(String namespace, Map<K, V> entries) {
        entries.forEach((key, value) ->
                redisTemplate.opsForValue().set(buildKey(namespace, key), value, ttl));
    }

    // ---- eviction -------------------------------------------------------

    @Override
    public void evict(String namespace) {
        var keys = redisTemplate.keys(KEY_PREFIX + namespace + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ---- private --------------------------------------------------------

    private String buildKey(String namespace, Object key) {
        return KEY_PREFIX + namespace + ":" + key;
    }

    @Override
    public String toString() {
        return "RedisCache{ttl=" + ttl + "}";
    }
}
