package com.kayz.asmer;

import com.kayz.asmer.internal.VirtualConcurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Strategy for executing multiple independent assembly rules.
 *
 * <p>Implement this interface to plug in any execution model — thread pools,
 * virtual threads, reactive schedulers, etc.
 *
 * <p>Built-in strategies (via factory methods):
 * <ul>
 *   <li>{@link #callerThread()} — sequential, same thread (default)</li>
 *   <li>{@link #platformThreads()} — parallel via {@link ForkJoinPool#commonPool()}</li>
 *   <li>{@link #platformThreads(int)} — per-call fixed pool, auto-shutdown after each use</li>
 *   <li>{@link #virtualThreads()} — one virtual thread per rule (Java 21+)</li>
 *   <li>{@link #executor(ExecutorService)} — bring your own executor</li>
 *   <li>{@link #perCall(Supplier)} — create and shutdown an executor per {@code executeAll} call</li>
 * </ul>
 *
 * <p>Strategies can be composed with {@link #withTimeout(Duration)}.
 */
public interface Concurrency {

    /**
     * Execute all tasks, blocking until every task completes.
     *
     * <p>If any task throws, implementations must still wait for all remaining tasks
     * before throwing. The first error is thrown; subsequent errors are added as
     * {@linkplain Throwable#addSuppressed suppressed} exceptions.
     *
     * @param tasks non-null, non-empty list of independent work units
     */
    void executeAll(List<Runnable> tasks);

    // ---- composition -----------------------------------------------------

    /**
     * Returns a new strategy that imposes a wall-clock timeout on {@link #executeAll}.
     * If the timeout elapses before all tasks finish, an {@link AssemblyException} is thrown.
     *
     * <p>Example:
     * <pre>{@code
     * Concurrency.platformThreads().withTimeout(Duration.ofSeconds(5))
     * }</pre>
     *
     * @param timeout positive duration
     */
    default Concurrency withTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new RuleDefinitionException("timeout must be positive, got " + timeout);
        }
        Concurrency inner = this;
        return tasks -> {
            ExecutorService guard = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "asmer-timeout-guard");
                t.setDaemon(true);
                return t;
            });
            Future<?> future = guard.submit(() -> inner.executeAll(tasks));
            try {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new AssemblyException("Assembly timed out after " + timeout, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err)          throw err;
                throw new AssemblyException("Assembly failed", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssemblyException("Assembly interrupted", e);
            } finally {
                guard.shutdownNow();
            }
        };
    }

    // ---- factory methods ------------------------------------------------

    /** Sequential execution in the calling thread. This is the default. */
    static Concurrency callerThread() {
        return tasks -> tasks.forEach(Runnable::run);
    }

    /**
     * Parallel execution using the JVM's shared {@link ForkJoinPool#commonPool()}.
     * No lifecycle management needed — the pool is owned by the JVM.
     */
    static Concurrency platformThreads() {
        return tasks -> runAsync(tasks, ForkJoinPool.commonPool());
    }

    /**
     * Parallel execution using a fresh fixed-size platform-thread pool created
     * for each {@link #executeAll} call and shut down immediately after.
     *
     * <p>This avoids resource leaks when {@link AsmerConfig} is not a singleton.
     * For high-throughput scenarios use {@link #executor(ExecutorService)} with a
     * long-lived, externally managed pool instead.
     *
     * @param poolSize number of worker threads; must be &gt; 0
     */
    static Concurrency platformThreads(int poolSize) {
        if (poolSize <= 0) throw new RuleDefinitionException("poolSize must be > 0, got " + poolSize);
        return tasks -> {
            ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
                Thread t = new Thread(r, "asmer-pool-" + poolSize);
                t.setDaemon(true);
                return t;
            });
            try {
                runAsync(tasks, pool);
            } finally {
                pool.shutdown();
            }
        };
    }

    /**
     * Parallel execution using Java 21+ virtual threads.
     * A fresh virtual-thread executor is created and shut down for each call.
     *
     * @throws RuleDefinitionException on Java &lt; 21
     */
    static Concurrency virtualThreads() {
        return VirtualConcurrency.INSTANCE;
    }

    /**
     * Parallel execution using a caller-supplied {@link ExecutorService}.
     * The executor is <em>not</em> shut down by the framework — the caller manages its lifecycle.
     *
     * <p>Prefer this over {@link #platformThreads(int)} when running many assemblies
     * per second and you want a single long-lived pool:
     * <pre>{@code
     * ExecutorService sharedPool = Executors.newFixedThreadPool(8);
     * Concurrency c = Concurrency.executor(sharedPool);
     * // ... reuse across many AsmerConfig / Asmer.of() calls ...
     * sharedPool.shutdown(); // caller owns lifecycle
     * }</pre>
     */
    static Concurrency executor(ExecutorService exec) {
        Objects.requireNonNull(exec, "exec");
        return tasks -> runAsync(tasks, exec);
    }

    /**
     * Creates a fresh executor for every {@link #executeAll} call using the supplied
     * factory, then shuts it down after all tasks complete.
     *
     * <p>This is useful for custom thread factories or per-call isolation:
     * <pre>{@code
     * // New cached pool every call — auto-shutdown included
     * Concurrency.perCall(Executors::newCachedThreadPool)
     *
     * // Custom thread factory
     * Concurrency.perCall(() -> Executors.newFixedThreadPool(4, myFactory))
     * }</pre>
     *
     * @param executorFactory called once per {@link #executeAll} invocation
     */
    static Concurrency perCall(Supplier<ExecutorService> executorFactory) {
        Objects.requireNonNull(executorFactory, "executorFactory");
        return tasks -> {
            ExecutorService exec = executorFactory.get();
            try {
                runAsync(tasks, exec);
            } finally {
                exec.shutdown();
            }
        };
    }

    // ---- shared helper --------------------------------------------------

    /**
     * Submits all tasks to {@code exec}, waits for every one to finish, then
     * throws the first error (with any subsequent errors added as suppressed).
     *
     * <p>Unlike a plain {@code CompletableFuture.allOf().join()}, this method
     * never abandons in-flight tasks on the first failure.
     */
    private static void runAsync(List<Runnable> tasks, ExecutorService exec) {
        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(t -> CompletableFuture.runAsync(t, exec))
                .toList();

        RuntimeException firstError = null;
        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (CompletionException e) {
                RuntimeException re = unwrapCompletionException(e);
                if (firstError == null) firstError = re;
                else firstError.addSuppressed(re);
            }
        }
        if (firstError != null) throw firstError;
    }

    private static RuntimeException unwrapCompletionException(CompletionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException re) return re;
        if (cause instanceof Error err)          throw err;
        return new AssemblyException("Async task failed", cause);
    }
}
