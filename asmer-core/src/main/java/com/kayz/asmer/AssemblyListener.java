package com.kayz.asmer;

/**
 * Callback invoked after each assembly rule completes.
 *
 * <pre>{@code
 * Asmer.of(orders)
 *     .listener(event -> log.info("[asmer] {} keys={} hits={} {}ms",
 *             event.ruleName(), event.keyCount(), event.cacheHits(),
 *             event.duration().toMillis()))
 *     .on(Order::getUser, userRepo::findByIdIn, User::getId)
 *     .assemble();
 * }</pre>
 *
 * <p>Exceptions thrown by the listener are silently swallowed so that
 * a buggy listener never aborts assembly.
 */
@FunctionalInterface
public interface AssemblyListener {

    void onAssembly(AssemblyEvent event);

    /** No-op listener — default when none is configured. */
    static AssemblyListener noop() {
        return event -> {};
    }

    // ---- global default (set by Spring auto-configuration) --------------

    java.util.concurrent.atomic.AtomicReference<AssemblyListener> GLOBAL =
            new java.util.concurrent.atomic.AtomicReference<>(noop());

    /** Returns the process-wide default listener (noop until overridden). */
    static AssemblyListener globalDefault() {
        return GLOBAL.get();
    }

    /** Replaces the global default. Called by Spring auto-configuration at startup. */
    static void setGlobalDefault(AssemblyListener listener) {
        GLOBAL.set(java.util.Objects.requireNonNull(listener, "listener"));
    }
}
