package com.kayz.asmer;

import java.util.Objects;

/**
 * Immutable configuration for an {@link Asmer} instance.
 *
 * <p>Build once and reuse across many {@link Asmer#of} calls:
 * <pre>{@code
 * // Application startup
 * AsmerConfig config = AsmerConfig.builder()
 *     .concurrency(Concurrency.platformThreads(4))
 *     .cache(myCaffeineCache)
 *     .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
 *     .build();
 *
 * // Per request
 * Asmer.of(orders, config)
 *     .on(Order::getUser, userService::batchGet, User::getId)
 *     .assemble();
 * }</pre>
 */
public final class AsmerConfig {

    /** Hard-coded fallback: caller-thread concurrency, no cache, THROW on error. */
    public static final AsmerConfig DEFAULT = new AsmerConfig(
            Concurrency.callerThread(), AsmerCache.none(), ErrorPolicy.THROW);

    /**
     * Global default used by {@link Asmer#of(java.util.List)} and other no-config factories.
     * Starts as {@link #DEFAULT}; overwritten by Spring auto-configuration at startup
     * so that YAML settings take effect without explicit injection.
     */
    private static volatile AsmerConfig globalDefault = DEFAULT;

    /**
     * Returns the current global default config.
     * In a Spring Boot application this reflects the values from {@code application.yaml}.
     */
    public static AsmerConfig globalDefault() {
        return globalDefault;
    }

    /**
     * Replaces the global default. Called by Spring auto-configuration at startup.
     * May also be called in non-Spring apps to set a process-wide default.
     */
    public static void setGlobalDefault(AsmerConfig config) {
        globalDefault = Objects.requireNonNull(config, "config");
    }

    private final Concurrency concurrency;
    private final AsmerCache cache;
    private final ErrorPolicy errorPolicy;

    private AsmerConfig(Concurrency concurrency, AsmerCache cache, ErrorPolicy errorPolicy) {
        this.concurrency = concurrency;
        this.cache = cache;
        this.errorPolicy = errorPolicy;
    }

    public Concurrency concurrency() { return concurrency; }
    public AsmerCache cache()        { return cache; }
    public ErrorPolicy errorPolicy() { return errorPolicy; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Concurrency concurrency = Concurrency.callerThread();
        private AsmerCache cache        = AsmerCache.none();
        private ErrorPolicy errorPolicy = ErrorPolicy.THROW;

        private Builder() {}

        /**
         * Execution strategy for parallel rule processing.
         * Default: {@link Concurrency#callerThread()}.
         */
        public Builder concurrency(Concurrency concurrency) {
            this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
            return this;
        }

        /**
         * Cache to use for loaded values.
         * Default: {@link AsmerCache#none()}.
         */
        public Builder cache(AsmerCache cache) {
            this.cache = Objects.requireNonNull(cache, "cache");
            return this;
        }

        /**
         * How to handle runtime assembly failures.
         * Default: {@link ErrorPolicy#THROW}.
         */
        public Builder errorPolicy(ErrorPolicy errorPolicy) {
            this.errorPolicy = Objects.requireNonNull(errorPolicy, "errorPolicy");
            return this;
        }

        public AsmerConfig build() {
            return new AsmerConfig(concurrency, cache, errorPolicy);
        }
    }

    @Override
    public String toString() {
        return "AsmerConfig{concurrency=" + concurrency +
               ", cache=" + cache +
               ", errorPolicy=" + errorPolicy + '}';
    }
}
