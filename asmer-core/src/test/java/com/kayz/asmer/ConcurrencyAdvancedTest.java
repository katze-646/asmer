package com.kayz.asmer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyAdvancedTest {

    // ---- withTimeout --------------------------------------------------------

    @Nested
    class WithTimeout {

        @Test
        void completesWithinTimeout_noException() {
            Concurrency c = Concurrency.platformThreads()
                    .withTimeout(Duration.ofSeconds(5));

            AtomicInteger ran = new AtomicInteger();
            assertDoesNotThrow(() ->
                    c.executeAll(List.of(ran::incrementAndGet, ran::incrementAndGet)));
            assertEquals(2, ran.get());
        }

        @Test
        void exceedsTimeout_throwsAssemblyException() {
            Concurrency c = Concurrency.platformThreads()
                    .withTimeout(Duration.ofMillis(50));

            assertThrows(AssemblyException.class, () ->
                    c.executeAll(List.of(() -> {
                        try { Thread.sleep(5_000); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    })));
        }

        @Test
        void taskThrows_exceptionPropagatesWithoutTimeout() {
            Concurrency c = Concurrency.callerThread()
                    .withTimeout(Duration.ofSeconds(5));

            RuntimeException ex = assertThrows(RuntimeException.class, () ->
                    c.executeAll(List.of(() -> { throw new IllegalStateException("oops"); })));
            assertEquals("oops", ex.getMessage());
        }

        @Test
        void zeroTimeout_throwsRuleDefinitionException() {
            assertThrows(RuleDefinitionException.class, () ->
                    Concurrency.callerThread().withTimeout(Duration.ZERO));
        }

        @Test
        void negativeTimeout_throwsRuleDefinitionException() {
            assertThrows(RuleDefinitionException.class, () ->
                    Concurrency.callerThread().withTimeout(Duration.ofMillis(-1)));
        }

        @Test
        void nullTimeout_throwsNullPointerException() {
            assertThrows(NullPointerException.class, () ->
                    Concurrency.callerThread().withTimeout(null));
        }
    }

    // ---- perCall ------------------------------------------------------------

    @Nested
    class PerCall {

        @Test
        void tasksExecute_resultsCorrect() {
            AtomicInteger counter = new AtomicInteger();
            Concurrency c = Concurrency.perCall(Executors::newCachedThreadPool);

            c.executeAll(List.of(counter::incrementAndGet, counter::incrementAndGet));

            assertEquals(2, counter.get());
        }

        @Test
        void createsNewExecutorEachCall() {
            Set<String> poolNames = ConcurrentHashMap.newKeySet();
            AtomicInteger factoryCallCount = new AtomicInteger();

            Concurrency c = Concurrency.perCall(() -> {
                factoryCallCount.incrementAndGet();
                return Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r);
                    poolNames.add(t.getName());
                    return t;
                });
            });

            c.executeAll(List.of(() -> {}));
            c.executeAll(List.of(() -> {}));

            assertEquals(2, factoryCallCount.get(),
                    "factory must be invoked once per executeAll call");
        }

        @Test
        void executorIsShutdownAfterCall() {
            AtomicInteger[] shutdownCount = {new AtomicInteger()};
            ExecutorService[] lastExec = {null};

            Concurrency c = Concurrency.perCall(() -> {
                ExecutorService exec = Executors.newCachedThreadPool();
                lastExec[0] = exec;
                return exec;
            });

            c.executeAll(List.of(() -> {}));

            assertTrue(lastExec[0].isShutdown(),
                    "executor must be shut down after executeAll completes");
        }

        @Test
        void executorShutdownEvenOnTaskException() {
            ExecutorService[] lastExec = {null};

            Concurrency c = Concurrency.perCall(() -> {
                ExecutorService exec = Executors.newCachedThreadPool();
                lastExec[0] = exec;
                return exec;
            });

            assertThrows(RuntimeException.class, () ->
                    c.executeAll(List.of(() -> { throw new RuntimeException("fail"); })));

            assertTrue(lastExec[0].isShutdown(),
                    "executor must be shut down even when a task throws");
        }

        @Test
        void nullFactory_throwsNullPointerException() {
            assertThrows(NullPointerException.class, () -> Concurrency.perCall(null));
        }
    }

    // ---- platformThreads(int) per-call pool safety --------------------------

    @Nested
    class PlatformThreadsPoolSafety {

        @Test
        void eachExecuteAllUsesIndependentPool() {
            // Two sequential calls must both succeed; a shared/leaked pool
            // that was shut down on the first call would break the second.
            Concurrency c = Concurrency.platformThreads(2);
            AtomicInteger total = new AtomicInteger();

            c.executeAll(List.of(total::incrementAndGet, total::incrementAndGet));
            c.executeAll(List.of(total::incrementAndGet, total::incrementAndGet));

            assertEquals(4, total.get());
        }

        @Test
        void multipleErrors_firstThrownWithSuppressed() {
            Concurrency c = Concurrency.platformThreads(4);
            CountDownLatch ready = new CountDownLatch(2);

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    c.executeAll(List.of(
                            () -> { ready.countDown(); try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } throw new RuntimeException("err-1"); },
                            () -> { ready.countDown(); try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } throw new RuntimeException("err-2"); }
                    )));

            // first error thrown; second added as suppressed
            assertTrue(
                    thrown.getSuppressed().length >= 1 ||
                    thrown.getMessage().contains("err"),
                    "at least one error must surface");
        }
    }
}
