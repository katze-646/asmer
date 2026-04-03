package com.kayz.asmer.cache.redis;

import com.kayz.asmer.AsmerCache;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * Stores all entries using a single Redis pipeline flush.
     *
     * <p>Compared to issuing one {@code SET} per entry, pipeline batches all commands
     * into a single network round-trip, reducing latency from O(N) to O(1) RTTs.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> void putAll(String namespace, Map<K, V> entries) {
        if (entries.isEmpty()) return;
        RedisSerializer<String> keySerializer   = (RedisSerializer<String>) redisTemplate.getKeySerializer();
        RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();
        Expiration expiration = Expiration.from(ttl);

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            entries.forEach((k, v) -> {
                byte[] redisKey   = keySerializer.serialize(buildKey(namespace, k));
                byte[] redisValue = valueSerializer.serialize(v);
                connection.stringCommands().set(redisKey, redisValue, expiration,
                        RedisStringCommands.SetOption.upsert());
            });
            return null;
        });
    }

    // ---- eviction -------------------------------------------------------

    /**
     * Removes all entries under the given namespace.
     *
     * <p>Uses {@code SCAN} instead of {@code KEYS} to avoid blocking the Redis
     * event loop on large keyspaces. {@code SCAN} is cursor-based and processes
     * keys in small batches, so other commands continue to be served between
     * each batch.
     */
    @Override
    public void evict(String namespace) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + namespace + ":*")
                .count(100)
                .build();

        List<String> toDelete = new ArrayList<>();
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                cursor.forEachRemaining(key -> toDelete.add(new String(key)));
            }
            return null;
        });

        if (!toDelete.isEmpty()) {
            redisTemplate.delete(toDelete);
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
