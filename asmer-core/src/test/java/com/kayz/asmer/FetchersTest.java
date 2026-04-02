package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class FetchersTest {

    // ---- fixtures --------------------------------------------------------

    static class User {
        final Long id;
        final String name;

        User(Long id, String name) { this.id = id; this.name = name; }

        public Long getId()     { return id; }
        public String getName() { return name; }
    }

    static class Order {
        final Long id;
        final Long userId;

        @AssembleOne(keyField = "userId")
        User user;

        Order(Long id, Long userId) { this.id = id; this.userId = userId; }

        public Long getId()            { return id; }
        public Long getUserId()        { return userId; }
        public User getUser()          { return user; }
        public void setUser(User user) { this.user = user; }
    }

    // ---- fromMap --------------------------------------------------------

    @Nested
    class FromMap {

        @Test
        void fromMap_returnsAllValuesFromMap() {
            User alice = new User(1L, "Alice");
            User bob   = new User(2L, "Bob");

            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.fromMap(ids -> Map.of(1L, alice, 2L, bob));

            List<User> result = fetcher.apply(List.of(1L, 2L));

            assertEquals(2, result.size());
            assertTrue(result.contains(alice));
            assertTrue(result.contains(bob));
        }

        @Test
        void fromMap_nullMapReturn_treatedAsEmptyList() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.fromMap(ids -> null);

            List<User> result = fetcher.apply(List.of(1L));

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void fromMap_emptyMapReturn_returnsEmptyList() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.fromMap(ids -> Map.of());

            List<User> result = fetcher.apply(List.of(1L));

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void fromMap_keysForwardedToUnderlyingFetcher() {
            List<Long> captured = new java.util.ArrayList<>();

            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.fromMap(ids -> {
                        captured.addAll(ids);
                        return Map.of();
                    });

            fetcher.apply(List.of(10L, 20L, 30L));

            assertEquals(3, captured.size());
            assertTrue(captured.containsAll(List.of(10L, 20L, 30L)));
        }

        @Test
        void fromMap_nullFetcher_throwsNullPointerException() {
            assertThrows(NullPointerException.class, () -> Fetchers.fromMap(null));
        }

        @Test
        void fromMap_integratesWithAsmer_manyToOne() {
            User alice = new User(10L, "Alice");
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 10L);

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser,
                            Fetchers.fromMap(ids -> Map.of(10L, alice)),
                            User::getId)
                    .assemble();

            assertEquals("Alice", o1.getUser().getName());
            assertEquals("Alice", o2.getUser().getName());
        }
    }

    // ---- parallel (common pool) -----------------------------------------

    @Nested
    class ParallelCommonPool {

        @Test
        void parallel_allKeysAreFetched() {
            Set<Long> fetched = ConcurrentHashMap.newKeySet();

            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.parallel((Long id) -> {
                        fetched.add(id);
                        return new User(id, "User-" + id);
                    });

            List<User> result = fetcher.apply(List.of(1L, 2L, 3L));

            assertEquals(3, result.size());
            assertEquals(Set.of(1L, 2L, 3L), fetched);
        }

        @Test
        void parallel_nullReturnFromFetcher_droppedFromResult() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.parallel((Long id) -> id == 2L ? null : new User(id, "User-" + id));

            List<User> result = fetcher.apply(List.of(1L, 2L, 3L));

            assertEquals(2, result.size());
            assertTrue(result.stream().noneMatch(u -> u.id == 2L));
        }

        @Test
        void parallel_emptyKeys_returnsEmptyList() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.parallel((Long id) -> new User(id, "x"));

            List<User> result = fetcher.apply(List.of());

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void parallel_nullFetcher_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> Fetchers.parallel((Function<Long, User>) null));
        }

        @Test
        void parallel_integratesWithAsmer_manyToOne() {
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 20L);
            AtomicInteger calls = new AtomicInteger();

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser,
                            Fetchers.parallel((Long id) -> {
                                calls.incrementAndGet();
                                return new User(id, "User-" + id);
                            }),
                            User::getId)
                    .assemble();

            assertEquals(2, calls.get());
            assertEquals("User-10", o1.getUser().getName());
            assertEquals("User-20", o2.getUser().getName());
        }
    }

    // ---- parallel (custom executor) -------------------------------------

    @Nested
    class ParallelCustomExecutor {

        @Test
        void parallelWithExecutor_allKeysAreFetched() {
            var executor = Executors.newFixedThreadPool(2);
            Set<Long> fetched = ConcurrentHashMap.newKeySet();

            try {
                Function<Collection<Long>, List<User>> fetcher =
                        Fetchers.parallel((Long id) -> {
                            fetched.add(id);
                            return new User(id, "User-" + id);
                        }, executor);

                List<User> result = fetcher.apply(List.of(1L, 2L, 3L));

                assertEquals(3, result.size());
                assertEquals(Set.of(1L, 2L, 3L), fetched);
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void parallelWithExecutor_nullReturn_droppedFromResult() {
            var executor = Executors.newSingleThreadExecutor();
            try {
                Function<Collection<Long>, List<User>> fetcher =
                        Fetchers.parallel(
                                (Long id) -> id == 99L ? null : new User(id, "x"),
                                executor);

                List<User> result = fetcher.apply(List.of(1L, 99L));

                assertEquals(1, result.size());
                assertEquals(1L, result.get(0).id);
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void parallelWithExecutor_executorNotShutDownByFetcher() {
            var executor = Executors.newSingleThreadExecutor();
            try {
                Function<Collection<Long>, List<User>> fetcher =
                        Fetchers.parallel((Long id) -> new User(id, "x"), executor);

                fetcher.apply(List.of(1L));

                assertFalse(executor.isShutdown(),
                        "Fetchers.parallel must not shut down a caller-provided executor");
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void parallelWithExecutor_nullFetcher_throwsNullPointerException() {
            var executor = Executors.newSingleThreadExecutor();
            try {
                assertThrows(NullPointerException.class,
                        () -> Fetchers.parallel(null, executor));
            } finally {
                executor.shutdown();
            }
        }

        @Test
        void parallelWithExecutor_nullExecutor_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> Fetchers.parallel((Long id) -> new User(id, "x"), null));
        }
    }

    // ---- sequential -----------------------------------------------------

    @Nested
    class Sequential {

        @Test
        void sequential_allKeysAreFetchedInCallerThread() {
            Thread callerThread = Thread.currentThread();
            Set<Thread> threads = ConcurrentHashMap.newKeySet();

            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.sequential((Long id) -> {
                        threads.add(Thread.currentThread());
                        return new User(id, "User-" + id);
                    });

            List<User> result = fetcher.apply(List.of(1L, 2L, 3L));

            assertEquals(3, result.size());
            assertEquals(1, threads.size(), "sequential must run in a single thread");
            assertTrue(threads.contains(callerThread));
        }

        @Test
        void sequential_preservesInsertionOrder() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.sequential((Long id) -> new User(id, "User-" + id));

            List<User> result = fetcher.apply(List.of(3L, 1L, 2L));

            assertEquals(3, result.size());
            assertEquals(3L, result.get(0).id);
            assertEquals(1L, result.get(1).id);
            assertEquals(2L, result.get(2).id);
        }

        @Test
        void sequential_nullReturn_droppedFromResult() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.sequential((Long id) -> id == 2L ? null : new User(id, "x"));

            List<User> result = fetcher.apply(List.of(1L, 2L, 3L));

            assertEquals(2, result.size());
            assertTrue(result.stream().noneMatch(u -> u.id == 2L));
        }

        @Test
        void sequential_emptyKeys_returnsEmptyList() {
            Function<Collection<Long>, List<User>> fetcher =
                    Fetchers.sequential((Long id) -> new User(id, "x"));

            List<User> result = fetcher.apply(List.of());

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        void sequential_nullFetcher_throwsNullPointerException() {
            assertThrows(NullPointerException.class,
                    () -> Fetchers.sequential(null));
        }

        @Test
        void sequential_integratesWithAsmer_manyToOne() {
            Order o1 = new Order(1L, 10L);
            Order o2 = new Order(2L, 20L);
            AtomicInteger calls = new AtomicInteger();

            Asmer.of(List.of(o1, o2))
                    .on(Order::getUser,
                            Fetchers.sequential((Long id) -> {
                                calls.incrementAndGet();
                                return new User(id, "User-" + id);
                            }),
                            User::getId)
                    .assemble();

            assertEquals(2, calls.get());
            assertEquals("User-10", o1.getUser().getName());
            assertEquals("User-20", o2.getUser().getName());
        }
    }
}
