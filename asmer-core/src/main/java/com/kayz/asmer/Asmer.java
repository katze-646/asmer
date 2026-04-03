package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import com.kayz.asmer.internal.AssemblyEngine;
import com.kayz.asmer.internal.LambdaIntrospector;
import com.kayz.asmer.internal.Reflections;
import com.kayz.asmer.internal.Rule;
import com.kayz.asmer.internal.RuleKind;
import com.kayz.asmer.internal.SerializableGetter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Data-first entry point for annotation-driven batch assembly.
 *
 * <h3>Quick start</h3>
 * <pre>{@code
 * // 1. Annotate your entity:
 * public class Order {
 *     @AssembleOne(keyField = "userId")
 *     private User user;
 *
 *     @AssembleMany(keyField = "id")
 *     private List<OrderItem> items;
 * }
 *
 * // 2. Assemble:
 * Asmer.of(orders)
 *     .on(Order::getUser,  userService::batchGetByIds,      User::getId)
 *     .on(Order::getItems, itemService::batchGetByOrderIds, OrderItem::getOrderId)
 *     .assemble();
 * }</pre>
 *
 * <h3>Inline configuration (fluent)</h3>
 * <pre>{@code
 * Asmer.of(orders)
 *     .cache(CaffeineCache.ttl(Duration.ofMinutes(5)))
 *     .concurrency(Concurrency.platformThreads())
 *     .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
 *     .on(Order::getUser,  userService::batchGetByIds, User::getId)
 *     .assemble();
 * }</pre>
 *
 * <h3>Shared config as base, with per-call override</h3>
 * <pre>{@code
 * // shared defaults (concurrency + error policy from config)
 * Asmer.of(orders, sharedConfig)
 *     .cache(localCache)    // override just the cache for this call
 *     .on(Order::getUser, ..., User::getId)
 *     .assemble();
 * }</pre>
 *
 * <h3>N+1 fallback (no batch API available)</h3>
 * <pre>{@code
 * Asmer.of(orders)
 *     .onEach(Order::getUser,  userId  -> userRpc.getUser(userId))
 *     .onEach(Order::getItems, orderId -> itemRpc.getItems(orderId))
 *     .assemble();
 * }</pre>
 *
 * @param <T> the parent entity type
 */
public final class Asmer<T> {

    private final Class<T>         entityType;
    private final List<T>          data;
    private final List<Rule<T, ?, ?>> rules = new ArrayList<>();

    // Per-instance config — initialized from AsmerConfig, overridable via fluent methods
    private Concurrency      concurrency;
    private AsmerCache       cache;
    private ErrorPolicy      errorPolicy;
    private AssemblyListener listener = AssemblyListener.globalDefault();

    private Asmer(Class<T> entityType, List<T> data, AsmerConfig config) {
        this.entityType  = entityType;
        this.data        = data;
        this.concurrency = config.concurrency();
        this.cache       = config.cache();
        this.errorPolicy = config.errorPolicy();
    }

    // ---- factories ------------------------------------------------------

    /**
     * Creates an {@code Asmer} using the global default configuration.
     * In a Spring Boot application the global default is set from {@code application.yaml}
     * by auto-configuration, so no explicit config injection is needed.
     *
     * <pre>{@code
     * // application.yaml sets concurrency + cache + errorPolicy automatically
     * Asmer.of(orders)
     *     .on(Order::getUser, userRepo::findByIdIn, User::getId)
     *     .assemble();
     * }</pre>
     *
     * @throws IllegalArgumentException if {@code data} is null or empty —
     *         use {@link #of(Class, List)} for empty lists
     */
    public static <T> Asmer<T> of(List<T> data) {
        return of(data, AsmerConfig.globalDefault());
    }

    /**
     * Creates an {@code Asmer} using the given {@link AsmerConfig} as the initial defaults.
     * Individual settings can be overridden via {@link #cache}, {@link #concurrency},
     * and {@link #errorPolicy} after construction.
     */
    @SuppressWarnings("unchecked")
    public static <T> Asmer<T> of(List<T> data, AsmerConfig config) {
        Objects.requireNonNull(config, "config");
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot infer entity type from null or empty list. Use Asmer.of(Class, List) instead.");
        }
        Class<T> type = (Class<T>) data.get(0).getClass();
        return new Asmer<>(type, data, config);
    }

    /** Convenience factory for a single entity. */
    public static <T> Asmer<T> of(T entity) {
        return of(List.of(entity));
    }

    /**
     * Creates an {@code Asmer} with an explicit entity type and default configuration.
     * Safe for empty lists — {@link #assemble()} becomes a no-op.
     */
    public static <T> Asmer<T> of(Class<T> type, List<T> data) {
        return of(type, data, AsmerConfig.DEFAULT);
    }

    /**
     * Creates an {@code Asmer} with an explicit entity type and the given config as defaults.
     */
    public static <T> Asmer<T> of(Class<T> type, List<T> data, AsmerConfig config) {
        Objects.requireNonNull(config, "config");
        return new Asmer<>(type, data != null ? data : List.of(), config);
    }

    // ---- per-instance configuration -------------------------------------

    /**
     * Sets the cache for this assembly. Overrides the cache from the config
     * (or the default no-op cache if no config was supplied).
     *
     * <pre>{@code
     * Asmer.of(orders)
     *     .cache(CaffeineCache.ttl(Duration.ofMinutes(5)))
     *     .on(...)
     *     .assemble();
     * }</pre>
     */
    public Asmer<T> cache(AsmerCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
        return this;
    }

    /**
     * Sets the concurrency strategy for this assembly. Overrides the strategy
     * from the config (or the default caller-thread strategy if no config was supplied).
     *
     * <pre>{@code
     * Asmer.of(orders)
     *     .concurrency(Concurrency.platformThreads())
     *     .on(...)
     *     .assemble();
     *
     * // With timeout:
     * Asmer.of(orders)
     *     .concurrency(Concurrency.virtualThreads().withTimeout(Duration.ofSeconds(10)))
     *     .on(...)
     *     .assemble();
     * }</pre>
     */
    public Asmer<T> concurrency(Concurrency concurrency) {
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
        return this;
    }

    /**
     * Sets the error policy for this assembly. Overrides the policy from the config
     * (or {@link ErrorPolicy#THROW} if no config was supplied).
     *
     * <pre>{@code
     * Asmer.of(orders)
     *     .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
     *     .on(...)
     *     .assemble();
     * }</pre>
     */
    public Asmer<T> errorPolicy(ErrorPolicy errorPolicy) {
        this.errorPolicy = Objects.requireNonNull(errorPolicy, "errorPolicy");
        return this;
    }

    /** Attaches a listener that receives metrics after each rule completes. */
    public Asmer<T> listener(AssemblyListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    // ---- batch loader ---------------------------------------------------

    /**
     * Registers a rule backed by a <em>batch</em> fetcher.
     *
     * <p>The field pointed to by {@code getter} must carry either
     * {@link AssembleOne} or {@link AssembleMany}:
     * <ul>
     *   <li><b>{@code @AssembleOne}</b>: {@code keyMapper} maps each returned value to its
     *       own key (e.g. {@code User::getId}). One value is set per parent.</li>
     *   <li><b>{@code @AssembleMany}</b>: {@code keyMapper} maps each child to its parent key
     *       (e.g. {@code OrderItem::getOrderId}). Results are grouped into a {@code List} per parent.</li>
     * </ul>
     *
     * <p>Keys are deduplicated before calling {@code fetcher} — exactly one batch call is made
     * regardless of how many entities share the same key.
     *
     * @param getter    unbound method reference to the annotated getter, e.g. {@code Order::getUser}
     * @param fetcher   batch function — receives deduplicated keys, returns all matching values
     * @param keyMapper for {@code @AssembleOne}: value's own key; for {@code @AssembleMany}: child's parent key
     * @throws RuleDefinitionException if the field lacks an annotation or cannot be resolved
     */
    public <K, V> Asmer<T> on(
            SerializableGetter<T, ?> getter,
            Function<Collection<K>, List<V>> fetcher,
            Function<V, K> keyMapper) {

        FieldInfo info = resolveFieldInfo(getter);
        Function<T, K> keyExtractor = Reflections.keyExtractor(entityType, info.keyFieldName);

        if (info.kind == RuleKind.ONE) {
            BiConsumer<T, V> setter = Reflections.setter(entityType, info.getterName);
            Function<Collection<K>, Map<K, V>> loader = keys ->
                    fetcher.apply(keys).stream()
                           .collect(Collectors.toMap(keyMapper, v -> v, (a, b) -> a));
            rules.add(new Rule<>(info.fieldName, keyExtractor, loader, setter, RuleKind.ONE));
        } else {
            @SuppressWarnings("unchecked")
            BiConsumer<T, List<V>> setter = (BiConsumer<T, List<V>>) (BiConsumer<T, ?>) Reflections.setter(entityType, info.getterName);
            Function<Collection<K>, Map<K, List<V>>> loader = keys -> {
                List<V> flat = fetcher.apply(keys);
                if (flat == null) return Map.of();
                return flat.stream().collect(Collectors.groupingBy(keyMapper));
            };
            rules.add(new Rule<>(info.fieldName, keyExtractor, loader, setter, RuleKind.MANY));
        }
        return this;
    }

    // ---- N+1 fallback ---------------------------------------------------

    /**
     * Registers a rule backed by a <em>single-item</em> fetcher (N+1 fallback).
     *
     * <p>Use this when no batch API exists. Keys are deduplicated before calling
     * the fetcher, so it is called at most once per unique key.
     *
     * @param getter  unbound method reference to the annotated getter
     * @param fetcher per-key function — called once per unique key
     */
    @SuppressWarnings("unchecked")
    public <K, V> Asmer<T> onEach(
            SerializableGetter<T, ?> getter,
            Function<K, V> fetcher) {

        FieldInfo info = resolveFieldInfo(getter);
        Function<T, K> keyExtractor = Reflections.keyExtractor(entityType, info.keyFieldName);
        BiConsumer<T, V> setter = (BiConsumer<T, V>) Reflections.setter(entityType, info.getterName);

        Function<Collection<K>, Map<K, V>> loader = keys -> {
            Map<K, V> result = new HashMap<>(keys.size());
            for (K key : keys) {
                V value = fetcher.apply(key);
                if (value != null) result.put(key, value);
            }
            return result;
        };

        rules.add(new Rule<>(info.fieldName, keyExtractor, loader, setter, info.kind));
        return this;
    }

    // ---- terminal -------------------------------------------------------

    /**
     * Executes all registered rules against the data supplied at construction time.
     * Blocks until every rule completes.
     *
     * <p>The effective configuration is determined by the initial config (or
     * {@link AsmerConfig#DEFAULT}) overridden by any values set via
     * {@link #cache}, {@link #concurrency}, and {@link #errorPolicy}.
     */
    public void assemble() {
        if (data.isEmpty()) return;
        AsmerConfig effective = AsmerConfig.builder()
                .concurrency(concurrency)
                .cache(cache)
                .errorPolicy(errorPolicy)
                .build();
        new AssemblyEngine(effective, listener).assemble(data, rules);
    }

    /**
     * Executes all registered rules asynchronously, returning a
     * {@link CompletableFuture} that completes when every rule has finished.
     *
     * <p>The assembly logic — including the configured {@link Concurrency} strategy,
     * {@link ErrorPolicy}, and {@link AssemblyListener} — behaves identically to
     * the synchronous {@link #assemble()}. Exceptions are propagated via
     * {@link java.util.concurrent.CompletionException}.
     *
     * <pre>{@code
     * CompletableFuture<Void> f = Asmer.of(orders)
     *     .on(Order::getUser, userRepo::findByIdIn, User::getId)
     *     .assembleAsync();
     * // do other work ...
     * f.join(); // or compose with other futures
     * }</pre>
     *
     * @return a future that completes normally when all rules succeed,
     *         or exceptionally if any rule throws and the policy is {@link ErrorPolicy#THROW}
     */
    /**
     * Executes all registered rules asynchronously using {@link ForkJoinPool#commonPool()}.
     * Equivalent to {@code assembleAsync(ForkJoinPool.commonPool())}.
     *
     * @return a future that completes normally when all rules succeed,
     *         or exceptionally (via {@link java.util.concurrent.CompletionException})
     *         if any rule throws and the policy is {@link ErrorPolicy#THROW}
     */
    public CompletableFuture<Void> assembleAsync() {
        return assembleAsync(ForkJoinPool.commonPool());
    }

    /**
     * Executes all registered rules asynchronously on the supplied {@link Executor}.
     *
     * <p>Use this overload when you need explicit control over the thread model, for example:
     * <ul>
     *   <li><b>Java 21+ virtual threads</b> (recommended for I/O-heavy loaders):
     *       {@code Executors.newVirtualThreadPerTaskExecutor()}</li>
     *   <li><b>Bounded pool with back-pressure</b>:
     *       {@code new ThreadPoolExecutor(n, n, 0, SECONDS, new ArrayBlockingQueue<>(cap), new CallerRunsPolicy())}</li>
     * </ul>
     *
     * <p><b>Avoid</b> {@code Executors.newFixedThreadPool} in high-throughput scenarios —
     * its internal {@code LinkedBlockingQueue} is unbounded and can cause OOM under sustained load
     * with no back-pressure signal.
     *
     * <p>The {@link Executor} is <em>not</em> shut down by the framework.
     *
     * @param executor executor that will run the assembly; must not be {@code null}
     * @return a future completing when all rules finish
     */
    public CompletableFuture<Void> assembleAsync(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        if (data.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(this::assemble, executor);
    }

    // ---- private helpers ------------------------------------------------

    private FieldInfo resolveFieldInfo(SerializableGetter<T, ?> getter) {
        String getterName = LambdaIntrospector.methodName(getter);
        String fieldName  = LambdaIntrospector.toFieldName(getterName);
        Field  field      = Reflections.findField(entityType, fieldName);

        AssembleOne  one  = field.getAnnotation(AssembleOne.class);
        AssembleMany many = field.getAnnotation(AssembleMany.class);

        if (one == null && many == null) {
            throw new RuleDefinitionException(
                    "Field '" + entityType.getSimpleName() + "." + fieldName +
                    "' must be annotated with @AssembleOne or @AssembleMany");
        }

        return new FieldInfo(
                fieldName,
                getterName,
                one != null ? one.keyField() : many.keyField(),
                one != null ? RuleKind.ONE : RuleKind.MANY
        );
    }

    private static final class FieldInfo {
        final String   fieldName;
        final String   getterName;
        final String   keyFieldName;
        final RuleKind kind;

        FieldInfo(String fieldName, String getterName, String keyFieldName, RuleKind kind) {
            this.fieldName    = fieldName;
            this.getterName   = getterName;
            this.keyFieldName = keyFieldName;
            this.kind         = kind;
        }
    }

    @Override
    public String toString() {
        return "Asmer<" + entityType.getSimpleName() + ">{rules=" + rules.size() +
               ", data=" + data.size() + '}';
    }
}
