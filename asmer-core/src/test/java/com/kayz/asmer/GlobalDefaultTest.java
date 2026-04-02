package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that AsmerConfig.setGlobalDefault / globalDefault() work correctly,
 * and that Asmer.of(data) (no-config overload) picks up the global default.
 */
class GlobalDefaultTest {

    // ---- fixtures -----------------------------------------------------------

    static class Order {
        private final Long id;
        private final Long userId;

        @AssembleOne(keyField = "userId")
        private User user;

        Order(Long id, Long userId) { this.id = id; this.userId = userId; }

        public Long getId()               { return id; }
        public Long getUserId()           { return userId; }
        public User getUser()             { return user; }
        public void setUser(User u)       { this.user = u; }
    }

    static class User {
        private final Long id;
        User(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    // Restore DEFAULT after each test so other tests are unaffected
    @BeforeEach
    @AfterEach
    void restoreDefault() {
        AsmerConfig.setGlobalDefault(AsmerConfig.DEFAULT);
    }

    // ---- globalDefault API --------------------------------------------------

    @Nested
    class GlobalDefaultApi {

        @Test
        void initialGlobalDefault_isDefaultConstant() {
            assertSame(AsmerConfig.DEFAULT, AsmerConfig.globalDefault());
        }

        @Test
        void setGlobalDefault_changesGlobalDefault() {
            AsmerConfig custom = AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                    .build();

            AsmerConfig.setGlobalDefault(custom);

            assertSame(custom, AsmerConfig.globalDefault());
        }

        @Test
        void setGlobalDefault_null_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> AsmerConfig.setGlobalDefault(null));
        }

        @Test
        void setGlobalDefault_doesNotMutateDefaultConstant() {
            AsmerConfig custom = AsmerConfig.builder().build();
            AsmerConfig.setGlobalDefault(custom);

            // DEFAULT constant must remain unchanged
            assertSame(Concurrency.callerThread().getClass(),
                    AsmerConfig.DEFAULT.concurrency().getClass());
            assertSame(ErrorPolicy.THROW, AsmerConfig.DEFAULT.errorPolicy());
        }
    }

    // ---- Asmer.of() uses globalDefault --------------------------------------

    @Nested
    class AsmerOfUsesGlobalDefault {

        @Test
        void ofNoConfig_usesGlobalDefault_cache() {
            AtomicInteger putCount = new AtomicInteger();
            AsmerCache spyCache = new AsmerCache() {
                @Override public <K, V> Optional<V> get(String ns, K key) { return Optional.empty(); }
                @Override public <K, V> void put(String ns, K key, V value) { putCount.incrementAndGet(); }
            };

            AsmerConfig.setGlobalDefault(AsmerConfig.builder().cache(spyCache).build());

            Order order = new Order(1L, 10L);
            Asmer.of(List.of(order))
                    .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                    .assemble();

            assertEquals(1, putCount.get(),
                    "Asmer.of() must use the global default cache");
        }

        @Test
        void ofNoConfig_usesGlobalDefault_errorPolicy() {
            AsmerConfig.setGlobalDefault(AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                    .build());

            Order order = new Order(1L, 10L);

            // LOG_AND_SKIP must swallow the loader exception
            assertDoesNotThrow(() ->
                    Asmer.of(List.of(order))
                            .on(Order::getUser,
                                    ids -> { throw new RuntimeException("RPC down"); },
                                    User::getId)
                            .assemble());
            assertNull(order.getUser());
        }

        @Test
        void ofNoConfig_afterDefaultRestored_usesDefaultBehavior() {
            // setGlobalDefault(DEFAULT) already called by @BeforeEach
            Order order = new Order(1L, 10L);

            // DEFAULT error policy is THROW
            assertThrows(AssemblyException.class, () ->
                    Asmer.of(List.of(order))
                            .on(Order::getUser,
                                    ids -> { throw new RuntimeException("fail"); },
                                    User::getId)
                            .assemble());
        }

        @Test
        void ofWithExplicitConfig_ignoresGlobalDefault() {
            // Set global default to LOG_AND_SKIP
            AsmerConfig.setGlobalDefault(AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                    .build());

            // Pass explicit THROW config — must win over global default
            AsmerConfig explicitThrow = AsmerConfig.builder()
                    .errorPolicy(ErrorPolicy.THROW)
                    .build();

            Order order = new Order(1L, 10L);
            assertThrows(AssemblyException.class, () ->
                    Asmer.of(List.of(order), explicitThrow)
                            .on(Order::getUser,
                                    ids -> { throw new RuntimeException("fail"); },
                                    User::getId)
                            .assemble());
        }

        @Test
        void fluentOverride_winsOverGlobalDefault() {
            // Global default uses a spy cache
            AtomicInteger putCount = new AtomicInteger();
            AsmerCache spyCache = new AsmerCache() {
                @Override public <K, V> Optional<V> get(String ns, K key) { return Optional.empty(); }
                @Override public <K, V> void put(String ns, K key, V value) { putCount.incrementAndGet(); }
            };
            AsmerConfig.setGlobalDefault(AsmerConfig.builder().cache(spyCache).build());

            // Fluent .cache(none()) must override global default's cache
            Order order = new Order(1L, 10L);
            Asmer.of(List.of(order))
                    .cache(AsmerCache.none())   // override
                    .on(Order::getUser, ids -> List.of(new User(10L)), User::getId)
                    .assemble();

            assertEquals(0, putCount.get(),
                    "fluent .cache() override must suppress the global-default cache");
        }
    }
}
