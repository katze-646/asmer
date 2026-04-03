package com.kayz.asmer.cache.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedisCache} against a local Redis instance (localhost:6379, DB 1).
 * DB 1 is flushed after each test; DB 0 is never touched.
 */
class RedisCacheTest {

    /** Use DB 1 so we never touch the user's default DB 0. */
    private static final int TEST_DB = 1;

    static RedisTemplate<String, Object> template;

    @BeforeAll
    static void setupTemplate() {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration("localhost", 6379);
        config.setDatabase(TEST_DB);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        factory.start();

        template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
    }

    @AfterEach
    void flushTestDb() {
        template.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    // ---- factory / construction -------------------------------------------

    @Nested
    class Factory {

        @Test
        void of_validArgs_returnsInstance() {
            assertNotNull(RedisCache.of(template, Duration.ofMinutes(5)));
        }

        @Test
        void of_nullTemplate_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> RedisCache.of(null, Duration.ofMinutes(5)));
        }

        @Test
        void of_nullTtl_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> RedisCache.of(template, null));
        }

        @Test
        void of_zeroTtl_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> RedisCache.of(template, Duration.ZERO));
        }

        @Test
        void of_negativeTtl_throwsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class,
                    () -> RedisCache.of(template, Duration.ofSeconds(-1)));
        }

        @Test
        void toString_containsTtl() {
            String s = RedisCache.of(template, Duration.ofMinutes(10)).toString();
            assertTrue(s.contains("10"), "toString must include TTL value");
        }
    }

    // ---- get / put ---------------------------------------------------------

    @Nested
    class GetPut {

        @Test
        void get_miss_returnsEmpty() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            assertFalse(cache.get("ns", "missing").isPresent());
        }

        @Test
        void put_thenGet_returnsStoredValue() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("ns", "k1", "Alice");

            Optional<String> result = cache.get("ns", "k1");
            assertTrue(result.isPresent());
            assertEquals("Alice", result.get());
        }

        @Test
        void put_differentNamespaces_isolated() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("users",  "1", "Alice");
            cache.put("orders", "1", "Order#1");

            assertEquals("Alice",   cache.<String, String>get("users",  "1").orElseThrow());
            assertEquals("Order#1", cache.<String, String>get("orders", "1").orElseThrow());
        }

        @Test
        void put_overwrite_returnsLatestValue() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("ns", "k1", "v1");
            cache.put("ns", "k1", "v2");

            assertEquals("v2", cache.<String, String>get("ns", "k1").orElseThrow());
        }
    }

    // ---- getAll / putAll ---------------------------------------------------

    @Nested
    class BatchOps {

        @Test
        void getAll_allHit_returnsAllValues() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("ns", "1", "Alice");
            cache.put("ns", "2", "Bob");

            Map<String, String> result = cache.getAll("ns", List.of("1", "2"));

            assertEquals(2, result.size());
            assertEquals("Alice", result.get("1"));
            assertEquals("Bob",   result.get("2"));
        }

        @Test
        void getAll_partialHit_returnsOnlyHits() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("ns", "1", "Alice");

            Map<String, String> result = cache.getAll("ns", List.of("1", "missing"));

            assertEquals(1, result.size());
            assertEquals("Alice", result.get("1"));
            assertFalse(result.containsKey("missing"));
        }

        @Test
        void getAll_allMiss_returnsEmptyMap() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            assertTrue(cache.getAll("ns", List.of("x", "y")).isEmpty());
        }

        @Test
        void getAll_emptyKeys_returnsEmptyMap() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            assertTrue(cache.getAll("ns", List.of()).isEmpty());
        }

        @Test
        void putAll_thenGetAll_returnsAllValues() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.putAll("ns", Map.of("1", "Alice", "2", "Bob"));

            Map<String, String> result = cache.getAll("ns", List.of("1", "2"));
            assertEquals(2, result.size());
            assertEquals("Alice", result.get("1"));
            assertEquals("Bob",   result.get("2"));
        }

        @Test
        void putAll_allEntriesHaveTtl() {
            // Pipeline implementation must preserve TTL on every entry
            Duration ttl = Duration.ofMinutes(10);
            RedisCache cache = RedisCache.of(template, ttl);
            Map<String, String> entries = Map.of("a", "v1", "b", "v2", "c", "v3",
                                                  "d", "v4", "e", "v5");
            cache.putAll("ns", entries);

            entries.keySet().forEach(k -> {
                Long expire = template.getExpire("asmer:ns:" + k, TimeUnit.SECONDS);
                assertNotNull(expire, "TTL entry missing for key " + k);
                assertTrue(expire > 0, "TTL must be positive for key " + k + ", got " + expire);
            });
        }

        @Test
        void putAll_emptyMap_isNoOp() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            assertDoesNotThrow(() -> cache.putAll("ns", Map.of()));
            assertTrue(cache.getAll("ns", List.of("x")).isEmpty());
        }
    }

    // ---- evict -------------------------------------------------------------

    @Nested
    class Evict {

        @Test
        void evict_removesAllEntriesUnderNamespace() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("users", "1", "Alice");
            cache.put("users", "2", "Bob");

            cache.evict("users");

            assertFalse(cache.<String, String>get("users", "1").isPresent());
            assertFalse(cache.<String, String>get("users", "2").isPresent());
        }

        @Test
        void evict_doesNotAffectOtherNamespaces() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            cache.put("users",  "1", "Alice");
            cache.put("orders", "1", "Order#1");

            cache.evict("users");

            assertFalse(cache.<String, String>get("users",  "1").isPresent());
            assertTrue(cache.<String, String>get("orders", "1").isPresent(),
                    "evict('users') must not touch other namespaces");
        }

        @Test
        void evict_nonExistentNamespace_noException() {
            RedisCache cache = RedisCache.of(template, Duration.ofMinutes(5));
            assertDoesNotThrow(() -> cache.evict("ghost"));
        }
    }
}
