package com.kayz.asmer;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Wraps a batch loader with a concurrency limit, preventing downstream overload
 * when many assembly calls fire simultaneously.
 *
 * <p>Internally uses a {@link Semaphore} to cap the number of in-flight loader
 * invocations. When no permit is available, the call fails immediately (non-blocking)
 * with an {@link AssemblyException} — pair with {@link ErrorPolicy#LOG_AND_SKIP} or
 * {@link ErrorPolicy#FALLBACK_EMPTY} to degrade gracefully rather than propagating
 * the error to callers.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // At most 10 concurrent calls to userRepo.findByIdIn
 * Function<Collection<Long>, List<User>> limited =
 *     RateLimit.perRule(userRepo::findByIdIn, 10);
 *
 * Asmer.of(orders)
 *     .errorPolicy(ErrorPolicy.FALLBACK_EMPTY)
 *     .on(Order::getUser, limited, User::getId)
 *     .assemble();
 * }</pre>
 */
public final class RateLimit {

    private RateLimit() {}

    /**
     * Wraps {@code loader} so that at most {@code maxConcurrent} invocations
     * can be in progress at the same time.
     *
     * <p>If no permit is available when a call arrives, an {@link AssemblyException}
     * is thrown immediately (try-acquire, no waiting). The permit is always released
     * in a {@code finally} block.
     *
     * @param loader         the batch fetcher to wrap; must not be {@code null}
     * @param maxConcurrent  maximum in-flight invocations; must be &gt; 0
     * @throws IllegalArgumentException if {@code maxConcurrent} is &lt;= 0
     */
    public static <K, V> Function<Collection<K>, List<V>> perRule(
            Function<Collection<K>, List<V>> loader,
            int maxConcurrent) {

        Objects.requireNonNull(loader, "loader");
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException(
                    "maxConcurrent must be > 0, got " + maxConcurrent);
        }

        Semaphore semaphore = new Semaphore(maxConcurrent);

        return keys -> {
            if (!semaphore.tryAcquire()) {
                throw new AssemblyException(
                        "Loader rate limit exceeded (maxConcurrent=" + maxConcurrent +
                        "): no permit available");
            }
            try {
                return loader.apply(keys);
            } finally {
                semaphore.release();
            }
        };
    }
}
