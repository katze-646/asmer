package com.kayz.asmer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Package-private: runs rules via Java 21+ virtual threads.
 * Discovered via {@link MethodHandle} so the module stays compilable on Java 17.
 */
final class VirtualConcurrency implements Concurrency {

    static final VirtualConcurrency INSTANCE = new VirtualConcurrency();

    private static final MethodHandle NEW_VT_EXECUTOR;

    static {
        MethodHandle mh = null;
        try {
            mh = MethodHandles.publicLookup().findStatic(
                    Executors.class,
                    "newVirtualThreadPerTaskExecutor",
                    MethodType.methodType(ExecutorService.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // Java < 21
        }
        NEW_VT_EXECUTOR = mh;
    }

    private VirtualConcurrency() {}

    static boolean isAvailable() {
        return NEW_VT_EXECUTOR != null;
    }

    @Override
    public void executeAll(List<Runnable> tasks) {
        if (NEW_VT_EXECUTOR == null) {
            throw new RuleDefinitionException(
                    "Concurrency.virtualThreads() requires Java 21+, running on " + Runtime.version());
        }
        ExecutorService exec;
        try {
            exec = (ExecutorService) NEW_VT_EXECUTOR.invoke();
        } catch (Throwable t) {
            throw new AssemblyException("Failed to create virtual-thread executor", t);
        }
        List<CompletableFuture<Void>> futures = tasks.stream()
                .map(r -> CompletableFuture.runAsync(r, exec))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err)          throw err;
            throw new AssemblyException("Virtual-thread rule execution failed", cause);
        } finally {
            exec.shutdown();
        }
    }
}
