package com.kayz.asmer;

import com.kayz.asmer.annotation.AssembleMany;
import com.kayz.asmer.annotation.AssembleOne;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AsmerAsyncTest {

    // ---- fixtures --------------------------------------------------------

    static class User {
        final int id;
        User(int id) { this.id = id; }
        int getId() { return id; }
    }

    static class Item {
        final int orderId;
        Item(int orderId) { this.orderId = orderId; }
        int getOrderId() { return orderId; }
    }

    static class Order {
        final int id;
        final int userId;

        @AssembleOne(keyField = "userId")
        User user;

        @AssembleMany(keyField = "id")
        List<Item> items;

        Order(int id, int userId) {
            this.id = id;
            this.userId = userId;
        }
        int getId()          { return id; }
        int getUserId()      { return userId; }
        User getUser()       { return user; }
        void setUser(User u) { this.user = u; }
        List<Item> getItems()       { return items; }
        void setItems(List<Item> i) { this.items = i; }
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
    void assembleAsync_returnsNonNullFuture() {
        List<Order> data = orders(2);
        CompletableFuture<Void> f = Asmer.of(data)
                .on(Order::getUser, this::userLoader, User::getId)
                .assembleAsync();
        assertNotNull(f);
        f.join();
    }

    @Test
    void assembleAsync_completesNormally_dataIsAssembled() {
        List<Order> data = orders(3);
        Asmer.of(data)
                .on(Order::getUser, this::userLoader, User::getId)
                .assembleAsync()
                .join();

        for (Order o : data) {
            assertNotNull(o.user, "user must be set for order " + o.id);
            assertEquals(o.userId, o.user.getId());
        }
    }

    @Test
    void assembleAsync_emptyData_returnsAlreadyCompletedFuture() {
        List<Order> data = new ArrayList<>();
        CompletableFuture<Void> f = Asmer.of(Order.class, data)
                .on(Order::getUser, this::userLoader, User::getId)
                .assembleAsync();
        // must be completed immediately, no blocking needed
        assertTrue(f.isDone());
        assertDoesNotThrow(f::join);
    }

    @Test
    void assembleAsync_loaderException_propagatesAsCompletionException() {
        List<Order> data = orders(1);
        CompletableFuture<Void> f = Asmer.of(data)
                .on(Order::getUser, ids -> { throw new RuntimeException("loader-boom"); }, User::getId)
                .assembleAsync();

        CompletionException ex = assertThrows(CompletionException.class, f::join);
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("loader-boom"),
                "cause should mention loader-boom, got: " + ex.getCause().getMessage());
    }

    @Test
    void assembleAsync_logAndSkip_futureCompletesNormally() {
        List<Order> data = orders(2);
        CompletableFuture<Void> f = Asmer.of(data)
                .errorPolicy(ErrorPolicy.LOG_AND_SKIP)
                .on(Order::getUser, ids -> { throw new RuntimeException("transient"); }, User::getId)
                .assembleAsync();

        assertDoesNotThrow(f::join, "LOG_AND_SKIP must not propagate the exception");
    }

    @Test
    void assembleAsync_multipleFutures_runConcurrently() throws InterruptedException {
        int parallelism = 4;
        CountDownLatch ready = new CountDownLatch(parallelism);
        CountDownLatch go    = new CountDownLatch(1);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < parallelism; i++) {
            List<Order> data = orders(1);
            CompletableFuture<Void> f = Asmer.of(data)
                    .on(Order::getUser, ids -> {
                        ready.countDown();
                        try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return userLoader(ids);
                    }, User::getId)
                    .assembleAsync();
            futures.add(f);
        }

        // Wait for all loaders to be in-flight simultaneously
        ready.await();
        go.countDown();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        // If we reach here without deadlock, concurrency works correctly
    }

    @Test
    void assembleAsync_listenerInvokedOnCompletion() {
        List<Order> data = orders(2);
        AtomicInteger callCount = new AtomicInteger();

        Asmer.of(data)
                .listener(event -> callCount.incrementAndGet())
                .on(Order::getUser, this::userLoader, User::getId)
                .assembleAsync()
                .join();

        assertEquals(1, callCount.get(), "listener must be called once per rule");
    }

    @Test
    void assembleAsync_withManyRules_allRulesExecuted() {
        List<Order> data = orders(2);
        AtomicInteger loaderCalls = new AtomicInteger();

        Asmer.of(data)
                .on(Order::getUser,  ids -> { loaderCalls.incrementAndGet(); return userLoader(ids); }, User::getId)
                .on(Order::getItems, ids -> { loaderCalls.incrementAndGet(); return List.of(); }, Item::getOrderId)
                .assembleAsync()
                .join();

        assertEquals(2, loaderCalls.get(), "both loaders must be called");
    }
}
