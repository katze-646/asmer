package com.kayz.asmer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory methods that adapt various RPC / HTTP fetch patterns into the
 * {@code Function<Collection<K>, List<V>>} shape expected by
 * {@link Asmer#on(com.kayz.asmer.internal.SerializableGetter, Function, Function)}.
 *
 * <h2>Usage patterns</h2>
 *
 * <h3>1. Batch HTTP endpoint returning a List (no adapter needed)</h3>
 * When the remote endpoint already returns a list keyed by a field on the
 * response object, pass the method reference directly:
 * <pre>{@code
 * // Spring RestClient — POST /api/users/batch  body:[1,2,3]  →  [{id:1,...},...]
 * Asmer.of(orders)
 *     .on(Order::getUser,
 *         ids -> restClient.post().uri("/api/users/batch")
 *                          .body(ids).retrieve()
 *                          .body(new ParameterizedTypeReference<List<User>>() {}),
 *         User::getId)
 *     .assemble();
 * }</pre>
 *
 * <h3>2. Batch endpoint returning a Map — use {@link #fromMap}</h3>
 * <pre>{@code
 * // Remote returns Map<Long, User> keyed by user id
 * Function<Collection<Long>, List<User>> fetcher = Fetchers.fromMap(
 *     ids -> restClient.post().uri("/api/users/map")
 *                      .body(ids).retrieve()
 *                      .body(new ParameterizedTypeReference<Map<Long,User>>() {}));
 *
 * Asmer.of(orders)
 *     .on(Order::getUser, fetcher, User::getId)
 *     .assemble();
 * }</pre>
 *
 * <h3>3. No batch API — use {@link #parallel} or {@link #sequential}</h3>
 * <pre>{@code
 * // One HTTP call per id, executed in parallel via ForkJoinPool
 * Function<Collection<Long>, List<User>> fetcher = Fetchers.parallel(
 *     id -> restClient.get().uri("/api/users/{id}", id)
 *                     .retrieve().body(User.class));
 *
 * Asmer.of(orders)
 *     .on(Order::getUser, fetcher, User::getId)
 *     .assemble();
 * }</pre>
 *
 * <h3>4. Custom executor for parallel single-key fetch — use {@link #parallel(Function, ExecutorService)}</h3>
 * <pre>{@code
 * ExecutorService httpPool = Executors.newFixedThreadPool(20);
 *
 * Function<Collection<Long>, List<User>> fetcher =
 *     Fetchers.parallel(id -> rpcClient.getUser(id), httpPool);
 * }</pre>
 */
public final class Fetchers {

    private Fetchers() {}

    // ---- map-returning endpoints ----------------------------------------

    /**
     * Adapts a batch fetcher whose return type is {@code Map<K, V>} to the
     * {@code Collection<K> → List<V>} shape expected by {@link Asmer#on}.
     *
     * <p>The key-mapper passed to {@code on()} is used by the framework to
     * rebuild the map internally, so the round-trip is correct.
     *
     * @param mapFetcher function that calls a remote endpoint and returns a map
     */
    public static <K, V> Function<Collection<K>, List<V>> fromMap(
            Function<Collection<K>, Map<K, V>> mapFetcher) {
        Objects.requireNonNull(mapFetcher, "mapFetcher");
        return keys -> {
            Map<K, V> result = mapFetcher.apply(keys);
            if (result == null || result.isEmpty()) return List.of();
            return new ArrayList<>(result.values());
        };
    }

    // ---- single-key → batch adapters ------------------------------------

    /**
     * Adapts a single-key fetcher into a batch fetcher by executing each key
     * in parallel using {@link ForkJoinPool#commonPool()}.
     *
     * <p>The assembly engine already deduplicates keys before calling this
     * fetcher, so each unique key is fetched exactly once.
     *
     * <p>Null values returned by the single fetcher are silently dropped.
     *
     * @param singleFetcher fetches one item by key; may return {@code null} on miss
     */
    public static <K, V> Function<Collection<K>, List<V>> parallel(
            Function<K, V> singleFetcher) {
        Objects.requireNonNull(singleFetcher, "singleFetcher");
        return keys -> keys.stream()
                .parallel()
                .map(singleFetcher)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Adapts a single-key fetcher into a batch fetcher by executing each key
     * in parallel using a custom {@link ExecutorService}.
     *
     * <p>The executor is <em>not</em> shut down by this method.
     *
     * @param singleFetcher fetches one item by key; may return {@code null} on miss
     * @param executor      executor used to run the individual fetches
     */
    public static <K, V> Function<Collection<K>, List<V>> parallel(
            Function<K, V> singleFetcher,
            ExecutorService executor) {
        Objects.requireNonNull(singleFetcher, "singleFetcher");
        Objects.requireNonNull(executor, "executor");
        return keys -> {
            List<CompletableFuture<V>> futures = keys.stream()
                    .map(k -> CompletableFuture.supplyAsync(() -> singleFetcher.apply(k), executor))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        };
    }

    /**
     * Adapts a single-key fetcher into a batch fetcher that runs sequentially
     * in the calling thread.
     *
     * <p>Prefer {@link Asmer#onEach} for the common sequential-fallback use case;
     * use this when you need the standard {@code on()} API with sequential execution.
     *
     * @param singleFetcher fetches one item by key; may return {@code null} on miss
     */
    public static <K, V> Function<Collection<K>, List<V>> sequential(
            Function<K, V> singleFetcher) {
        Objects.requireNonNull(singleFetcher, "singleFetcher");
        return keys -> keys.stream()
                .map(singleFetcher)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
