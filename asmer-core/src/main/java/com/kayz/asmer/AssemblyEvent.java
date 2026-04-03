package com.kayz.asmer;

import java.time.Duration;

/**
 * Snapshot of metrics for a single rule execution.
 * Delivered to {@link AssemblyListener} after every rule completes (success or failure).
 *
 * @param ruleName  field name the rule targets (e.g. {@code "user"}, {@code "items"})
 * @param keyCount  unique non-null keys collected before loading
 * @param cacheHits number of keys served from cache (0 when no cache configured)
 * @param duration  wall-clock time for the full rule execution
 * @param success   {@code false} if the loader threw an exception
 */
public record AssemblyEvent(
        String   ruleName,
        int      keyCount,
        int      cacheHits,
        Duration duration,
        boolean  success
) {}
