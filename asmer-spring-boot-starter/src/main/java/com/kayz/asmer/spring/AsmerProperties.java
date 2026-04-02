package com.kayz.asmer.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot configuration properties for Asmer.
 *
 * <pre>{@code
 * asmer:
 *   concurrency:
 *     strategy: platform_threads   # caller_thread | platform_threads | virtual_threads
 *     pool-size: 4
 *   cache:
 *     type: caffeine               # none | caffeine | redis
 *     ttl: 5m
 *     maximum-size: 10000
 *   error-policy: log_and_skip     # throw | log_and_skip | fallback_empty
 * }</pre>
 */
@ConfigurationProperties(prefix = "asmer")
public class AsmerProperties {

    private Concurrency concurrency = new Concurrency();
    private Cache cache             = new Cache();
    private ErrorPolicyChoice errorPolicy = ErrorPolicyChoice.THROW;

    public Concurrency getConcurrency()             { return concurrency; }
    public void setConcurrency(Concurrency c)       { this.concurrency = c; }
    public Cache getCache()                         { return cache; }
    public void setCache(Cache cache)               { this.cache = cache; }
    public ErrorPolicyChoice getErrorPolicy()       { return errorPolicy; }
    public void setErrorPolicy(ErrorPolicyChoice p) { this.errorPolicy = p; }

    // ---- concurrency ----------------------------------------------------

    public static class Concurrency {
        /**
         * Execution strategy for parallel rule processing.
         * {@code auto} selects virtual threads on Java 21+, otherwise platform threads.
         */
        private StrategyChoice strategy = StrategyChoice.AUTO;

        /** Thread pool size for {@code platform_threads} mode. */
        private int poolSize = Runtime.getRuntime().availableProcessors();

        public StrategyChoice getStrategy() { return strategy; }
        public void setStrategy(StrategyChoice s) { this.strategy = s; }
        public int getPoolSize()            { return poolSize; }
        public void setPoolSize(int size)   { this.poolSize = size; }

        public enum StrategyChoice {
            AUTO, CALLER_THREAD, PLATFORM_THREADS, VIRTUAL_THREADS
        }
    }

    // ---- cache ----------------------------------------------------------

    public static class Cache {
        private CacheType type         = CacheType.NONE;
        private Duration ttl           = Duration.ofMinutes(5);
        private long maximumSize       = 10_000;

        public CacheType getType()           { return type; }
        public void setType(CacheType t)     { this.type = t; }
        public Duration getTtl()             { return ttl; }
        public void setTtl(Duration ttl)     { this.ttl = ttl; }
        public long getMaximumSize()         { return maximumSize; }
        public void setMaximumSize(long s)   { this.maximumSize = s; }

        public enum CacheType { NONE, CAFFEINE, REDIS }
    }

    // ---- error policy ---------------------------------------------------

    public enum ErrorPolicyChoice { THROW, LOG_AND_SKIP, FALLBACK_EMPTY }
}
