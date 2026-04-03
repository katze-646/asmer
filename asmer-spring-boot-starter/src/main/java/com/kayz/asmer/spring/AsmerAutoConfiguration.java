package com.kayz.asmer.spring;

import com.kayz.asmer.AsmerCache;
import com.kayz.asmer.AsmerConfig;
import com.kayz.asmer.AssemblyListener;
import com.kayz.asmer.Concurrency;
import com.kayz.asmer.ErrorPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration
@EnableConfigurationProperties(AsmerProperties.class)
public class AsmerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AsmerConfig asmerConfig(AsmerProperties props,
                                   AsmerCache asmerCache) {
        AsmerConfig config = AsmerConfig.builder()
                .concurrency(resolveConcurrency(props.getConcurrency()))
                .cache(asmerCache)
                .errorPolicy(resolveErrorPolicy(props.getErrorPolicy()))
                .build();
        // Make this config the process-wide default so that Asmer.of(data) (no-arg)
        // automatically picks up YAML settings without explicit injection.
        AsmerConfig.setGlobalDefault(config);
        return config;
    }

    // ---- metrics (Micrometer — optional) ----------------------------------------

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerConfig {

        @Bean
        @ConditionalOnMissingBean(AssemblyListener.class)
        @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        public AssemblyListener assemblyListener(io.micrometer.core.instrument.MeterRegistry registry) {
            MicrometerAssemblyListener listener = new MicrometerAssemblyListener(registry);
            AssemblyListener.setGlobalDefault(listener);
            return listener;
        }
    }

    // ---- cache beans (Caffeine > no-op; Redis: auto-configured by asmer-cache-redis) ---

    @Configuration
    @ConditionalOnClass(name = "com.kayz.asmer.cache.caffeine.CaffeineCache")
    @ConditionalOnProperty(prefix = "asmer.cache", name = "type", havingValue = "caffeine")
    static class CaffeineCacheConfig {

        @Bean
        @ConditionalOnMissingBean(AsmerCache.class)
        public AsmerCache asmerCaffeineCache(AsmerProperties props) {
            try {
                Class<?> cls = Class.forName("com.kayz.asmer.cache.caffeine.CaffeineCache");
                var method = cls.getMethod("ttl", java.time.Duration.class, long.class);
                return (AsmerCache) method.invoke(
                        null,
                        props.getCache().getTtl(),
                        props.getCache().getMaximumSize());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "asmer.cache.type=caffeine requires asmer-cache-caffeine on the classpath", e);
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(AsmerCache.class)
    public AsmerCache asmerNoOpCache() {
        return AsmerCache.none();
    }

    // ---- helpers --------------------------------------------------------

    private static Concurrency resolveConcurrency(AsmerProperties.Concurrency cfg) {
        return switch (cfg.getStrategy()) {
            case CALLER_THREAD    -> Concurrency.callerThread();
            case PLATFORM_THREADS -> Concurrency.platformThreads(cfg.getPoolSize());
            case VIRTUAL_THREADS  -> Concurrency.virtualThreads();
            case AUTO             -> autoDetectConcurrency(cfg);
        };
    }

    private static Concurrency autoDetectConcurrency(AsmerProperties.Concurrency cfg) {
        boolean java21Plus = Runtime.version().feature() >= 21;
        return java21Plus
                ? Concurrency.virtualThreads()
                : Concurrency.platformThreads(cfg.getPoolSize());
    }

    private static ErrorPolicy resolveErrorPolicy(AsmerProperties.ErrorPolicyChoice choice) {
        return switch (choice) {
            case THROW          -> ErrorPolicy.THROW;
            case LOG_AND_SKIP   -> ErrorPolicy.LOG_AND_SKIP;
            case FALLBACK_EMPTY -> ErrorPolicy.FALLBACK_EMPTY;
        };
    }
}
