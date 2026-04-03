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
 * <h3>实现契约（@implSpec）</h3>
 * <ul>
 *   <li><b>线程安全</b>：{@code get} / {@code put} / {@code getAll} / {@code putAll}
 *       可能被多个线程同时调用（当配置了并发策略时）。实现必须保证线程安全。</li>
 *   <li><b>Null 语义</b>：{@code get} 返回 {@link java.util.Optional#empty()} 表示未命中，
 *       绝不返回包含 {@code null} 的 Optional；{@code put} 的 {@code value} 参数永远不为 {@code null}。</li>
 *   <li><b>Namespace 隔离</b>：不同 {@code namespace}（即 rule 名称）之间的 key 相互独立，
 *       实现必须将 namespace 纳入存储 key 的组成部分（如 {@code namespace + ":" + key}）。</li>
 *   <li><b>无副作用异常</b>：{@code get} / {@code getAll} 出现异常时，框架会降级为直接调用 loader，
 *       不会中止 assembly；{@code put} / {@code putAll} 的写入失败同样应当静默处理或记录日志，
 *       不允许向上抛出异常。</li>
 * </ul>
 *
 * <h3>最简自定义实现示例</h3>
 * <pre>{@code
 * // 用 ConcurrentHashMap 实现一个简单的进程内缓存
 * AsmerCache myCache = new AsmerCache() {
 *     private final Map<String, Object> store = new ConcurrentHashMap<>();
 *
 *     @Override
 *     @SuppressWarnings("unchecked")
 *     public <K, V> Optional<V> get(String namespace, K key) {
 *         return Optional.ofNullable((V) store.get(namespace + ":" + key));
 *     }
 *
 *     @Override
 *     public <K, V> void put(String namespace, K key, V value) {
 *         store.put(namespace + ":" + key, value);
 *     }
 * };
 *
 * // 在链式 API 中使用
 * Asmer.of(orders)
 *     .cache(myCache)
 *     .on(Order::getUser, userRepo::findByIdIn, User::getId)
 *     .assemble();
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
