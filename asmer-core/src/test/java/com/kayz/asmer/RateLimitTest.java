package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

    // ---- fixtures --------------------------------------------------------

    static class User {
        final int id;
        User(int id) { this.id = id; }
        int getId() { return id; }
    }

    static class Order {
        final int id;
        final int userId;

        @AssembleOne(keyField = "userId")
        User user;

        Order(int id, int userId) { this.id = id; this.userId = userId; }
        int getId()          { return id; }
        int getUserId()      { return userId; }
        User getUser()       { return user; }
        void setUser(User u) { this.user = u; }
    }

    private List<Order> orders(int count) {
        List<Order> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) list.add(new Order(i, i));
        return list;
    }

    private List<User> userLoader(Collection<Integer> ids) {
        return ids.stream().map(User::new).collect(Collectors.toList());
    }

    // ---- tests -----------------------------------------------------------

    @Test
    void rateLimit_underLimit_completesNormally() {
        List<Order> data = orders(3);
        assertDoesNotThrow(() ->
            Asmer.of(data)
                .on(Order::getUser,
                    RateLimit.perRule(this::userLoader, 5),
                    User::getId)
                .assemble()
        );
        data.forEach(o -> assertNotNull(o.user));
    }

    @Test
    void rateLimit_permitReleasedAfterLoader() {
        AtomicInteger callCount = new AtomicInteger();
        var limitedLoader = RateLimit.perRule(
            (Collection<Integer> ids) -> {
                callCount.incrementAndGet();
                return ids.stream().map(User::new).collect(Collectors.toList());
            }, 1);

        // First call
        Asmer.of(orders(2))
            .on(Order::getUser, limitedLoader, User::getId)
            .assemble();

        // Second call with same limited loader — permit must have been released
        List<Order> data2 = orders(2);
        assertDoesNotThrow(() ->
            Asmer.of(data2)
                .on(Order::getUser, limitedLoader, User::getId)
                .assemble()
        );
        assertEquals(2, callCount.get());
    }

    @Test
    void rateLimit_zeroPermits_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
            () -> RateLimit.perRule(this::userLoader, 0));
    }

    @Test
    void rateLimit_negativePermits_throwsOnConstruction() {
        assertThrows(IllegalArgumentException.class,
            () -> RateLimit.perRule(this::userLoader, -1));
    }

    @Test
    void rateLimit_noPermitAvailable_throwsAssemblyException() {
        // Set maxConcurrent=1, block the permit with a latch,
        // then attempt a second concurrent call via assembleAsync
        CountDownLatch loaderStarted  = new CountDownLatch(1);
        CountDownLatch releaseLoader  = new CountDownLatch(1);

        var blockingLoader = RateLimit.perRule(
            (Collection<Integer> ids) -> {
                loaderStarted.countDown();
                try { releaseLoader.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ids.stream().map(User::new).collect(Collectors.toList());
            }, 1);

        // First call acquires the single permit and blocks
        List<Order> data1 = orders(1);
        var f1 = Asmer.of(data1)
            .on(Order::getUser, blockingLoader, User::getId)
            .assembleAsync();

        try { loaderStarted.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // Second call should fail immediately — no permits available
        List<Order> data2 = orders(1);
        AssemblyException ex = assertThrows(AssemblyException.class, () ->
            Asmer.of(data2)
                .on(Order::getUser, blockingLoader, User::getId)
                .assemble()
        );
        assertTrue(ex.getMessage().contains("rate limit") || ex.getMessage().contains("permit"),
            "exception must mention rate limit, got: " + ex.getMessage());

        // Release the first call
        releaseLoader.countDown();
        f1.join();
    }
}
