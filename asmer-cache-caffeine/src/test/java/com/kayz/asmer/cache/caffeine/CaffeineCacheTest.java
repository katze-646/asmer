package com.kayz.asmer.cache.caffeine;

import com.kayz.asmer.Asmer;
import com.kayz.asmer.AsmerConfig;
import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineCacheTest {

    static class Order {
        private final Long id;
        private final Long userId;

        @AssembleOne(keyField = "userId")
        private User user;

        Order(Long id, Long userId) { this.id = id; this.userId = userId; }

        public Long getId()              { return id; }
        public Long getUserId()          { return userId; }
        public User getUser()            { return user; }
        public void setUser(User user)   { this.user = user; }
    }

    static class User {
        private final Long id;
        private final String name;

        User(Long id, String name) { this.id = id; this.name = name; }

        public Long getId()     { return id; }
        public String getName() { return name; }
    }

    @Test
    void secondAssemble_fullyCachedSkipsLoader() {
        AtomicInteger loaderCalls = new AtomicInteger(0);
        User alice = new User(10L, "Alice");

        AsmerConfig config = AsmerConfig.builder()
                .cache(CaffeineCache.ttl(Duration.ofMinutes(5)))
                .build();

        // First assemble — loader is called
        Order o1 = new Order(1L, 10L);
        Asmer.of(List.of(o1), config)
                .on(Order::getUser, ids -> { loaderCalls.incrementAndGet(); return List.of(alice); }, User::getId)
                .assemble();

        assertEquals(1, loaderCalls.get());
        assertEquals("Alice", o1.getUser().getName());

        // Second assemble with same userId — loader must NOT be called (cache hit)
        Order o2 = new Order(2L, 10L);
        Asmer.of(List.of(o2), config)
                .on(Order::getUser, ids -> { loaderCalls.incrementAndGet(); return List.of(alice); }, User::getId)
                .assemble();

        assertEquals(1, loaderCalls.get(), "loader must not be called on second assemble; values served from cache");
        assertEquals("Alice", o2.getUser().getName());
    }

    @Test
    void constructorValidation_negativeTtlThrows() {
        assertThrows(IllegalArgumentException.class, () -> CaffeineCache.ttl(Duration.ofMinutes(-1)));
    }

    @Test
    void constructorValidation_zeroMaxSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> CaffeineCache.ttl(Duration.ofMinutes(5), 0));
    }

    @Test
    void evict_clearsAllEntriesForNamespace() {
        CaffeineCache cache = CaffeineCache.ttl(Duration.ofMinutes(5));
        cache.put("rule1", 1L, "alice");
        assertTrue(cache.get("rule1", 1L).isPresent());

        cache.evict("rule1");
        assertFalse(cache.get("rule1", 1L).isPresent());
    }
}
