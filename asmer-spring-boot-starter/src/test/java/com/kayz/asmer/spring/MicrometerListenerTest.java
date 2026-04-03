package com.kayz.asmer.spring;

import com.kayz.asmer.Asmer;
import com.kayz.asmer.AssemblyListener;
import com.kayz.asmer.annotation.AssembleOne;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MicrometerAssemblyListener}.
 * Uses SimpleMeterRegistry — no Spring context needed.
 */
class MicrometerListenerTest {

    static class Order {
        private final Long userId;

        @AssembleOne(keyField = "userId")
        private User user;

        Order(Long userId) { this.userId = userId; }

        public Long getUserId()          { return userId; }
        public User getUser()            { return user; }
        public void setUser(User user)   { this.user = user; }
    }

    static class User {
        private final Long id;
        User(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    MeterRegistry registry;
    AssemblyListener listener;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        listener = new MicrometerAssemblyListener(registry);
    }

    @Test
    void constructor_nullRegistry_throws() {
        assertThrows(NullPointerException.class, () -> new MicrometerAssemblyListener(null));
    }

    @Test
    void successfulRule_recordsTimer() {
        Order order = new Order(1L);

        Asmer.of(List.of(order))
                .listener(listener)
                .on(Order::getUser, ids -> List.of(new User(1L)), User::getId)
                .assemble();

        Timer timer = registry.find("asmer.rule")
                .tag("rule", "user")
                .tag("success", "true")
                .timer();

        assertNotNull(timer, "timer 'asmer.rule' with rule=user, success=true must be registered");
        assertEquals(1, timer.count());
    }

    @Test
    void failedRule_recordsTimerWithSuccessFalse() {
        Order order = new Order(1L);

        assertThrows(Exception.class, () ->
                Asmer.of(List.of(order))
                        .listener(listener)
                        .on(Order::getUser, ids -> { throw new RuntimeException("down"); }, User::getId)
                        .assemble()
        );

        Timer timer = registry.find("asmer.rule")
                .tag("rule", "user")
                .tag("success", "false")
                .timer();

        assertNotNull(timer, "timer must be recorded even on failure");
        assertEquals(1, timer.count());
    }

    @Test
    void multipleRules_eachRecordedSeparately() {
        // Call twice with same rule name → count should be 2
        Order o1 = new Order(1L);
        Order o2 = new Order(2L);

        Asmer.of(List.of(o1))
                .listener(listener)
                .on(Order::getUser, ids -> List.of(new User(1L)), User::getId)
                .assemble();

        Asmer.of(List.of(o2))
                .listener(listener)
                .on(Order::getUser, ids -> List.of(new User(2L)), User::getId)
                .assemble();

        Timer timer = registry.find("asmer.rule")
                .tag("rule", "user")
                .tag("success", "true")
                .timer();

        assertNotNull(timer);
        assertEquals(2, timer.count(), "timer count must accumulate across calls");
    }

    @Test
    void globalDefault_setByAutoConfig_usedByAsmerOf() {
        Order order = new Order(1L);
        AssemblyListener.setGlobalDefault(listener);

        try {
            Asmer.of(List.of(order))
                    .on(Order::getUser, ids -> List.of(new User(1L)), User::getId)
                    .assemble();

            Timer timer = registry.find("asmer.rule")
                    .tag("rule", "user")
                    .timer();
            assertNotNull(timer, "global-default listener must be called by Asmer.of() with no explicit listener");
        } finally {
            AssemblyListener.setGlobalDefault(AssemblyListener.noop()); // restore
        }
    }
}
