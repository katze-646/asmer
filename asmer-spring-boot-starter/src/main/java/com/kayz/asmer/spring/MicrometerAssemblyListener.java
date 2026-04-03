package com.kayz.asmer.spring;

import com.kayz.asmer.AssemblyEvent;
import com.kayz.asmer.AssemblyListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

/**
 * {@link AssemblyListener} that records per-rule execution metrics via Micrometer.
 *
 * <h2>Metrics emitted</h2>
 * <table>
 *   <tr><th>Name</th><th>Tags</th><th>Description</th></tr>
 *   <tr><td>{@code asmer.rule}</td><td>{@code rule}, {@code success}</td>
 *       <td>Timer: wall-clock time for each rule execution</td></tr>
 * </table>
 *
 * <p>Auto-configured by {@link AsmerAutoConfiguration} when {@code micrometer-core}
 * is on the classpath. The listener is set as the global default so that
 * {@code Asmer.of(data)} picks it up without explicit injection.
 */
public final class MicrometerAssemblyListener implements AssemblyListener {

    private static final String METRIC_NAME = "asmer.rule";

    private final MeterRegistry registry;

    public MicrometerAssemblyListener(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onAssembly(AssemblyEvent event) {
        Timer.builder(METRIC_NAME)
                .tag("rule",    event.ruleName())
                .tag("success", String.valueOf(event.success()))
                .description("Asmer rule execution time")
                .register(registry)
                .record(event.duration());
    }
}
