package com.kayz.asmer.internal;

import com.kayz.asmer.AsmerCache;
import com.kayz.asmer.AsmerConfig;
import com.kayz.asmer.AssemblyEvent;
import com.kayz.asmer.AssemblyException;
import com.kayz.asmer.AssemblyListener;
import com.kayz.asmer.ErrorPolicy;
import com.kayz.asmer.RuleDefinitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Core execution engine. Stateless per invocation; all input is passed as parameters.
 */
public final class AssemblyEngine {

    private static final Logger log = LoggerFactory.getLogger(AssemblyEngine.class);

    private final AsmerConfig       config;
    private final AssemblyListener  listener;

    public AssemblyEngine(AsmerConfig config, AssemblyListener listener) {
        this.config   = config;
        this.listener = Objects.requireNonNull(listener);
    }

    /**
     * Executes all rules against the given entities.
     * Independent rules are submitted to the configured {@link com.kayz.asmer.Concurrency} strategy.
     */
    public <T> void assemble(List<T> entities, List<Rule<T, ?, ?>> rules) {
        if (entities.isEmpty() || rules.isEmpty()) return;

        if (rules.size() == 1) {
            executeRuleSafe(rules.get(0), entities);
            return;
        }

        List<Runnable> tasks = new ArrayList<>(rules.size());
        for (Rule<T, ?, ?> rule : rules) {
            tasks.add(() -> executeRuleSafe(rule, entities));
        }
        config.concurrency().executeAll(tasks);
    }

    // ---- rule execution -------------------------------------------------

    private <T, K, V> void executeRuleSafe(Rule<T, K, V> rule, List<T> entities) {
        long start = System.nanoTime();
        int[] metrics = {0, 0}; // [0]=keyCount, [1]=cacheHits
        boolean success = true;
        try {
            doExecuteRule(rule, entities, metrics);
        } catch (RuleDefinitionException e) {
            throw e; // programming error — always propagate
        } catch (AssemblyException e) {
            success = false;
            handleError(rule, entities, e);
        } catch (Exception e) {
            success = false;
            handleError(rule, entities,
                    new AssemblyException("Rule '" + rule.name() + "' failed unexpectedly: " + e.getMessage(), e));
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            AssemblyEvent event = new AssemblyEvent(rule.name(), metrics[0], metrics[1], duration, success);
            try {
                listener.onAssembly(event);
            } catch (Exception ignored) {
                // listener exceptions must never abort assembly
            }
        }
    }

    private <T, K, V> void doExecuteRule(Rule<T, K, V> rule, List<T> entities, int[] metrics) {
        // 1. Collect unique non-null keys
        List<K> allKeys = collectKeys(rule, entities);
        metrics[0] = allKeys.size(); // keyCount

        if (allKeys.isEmpty()) {
            applyDefault(rule, entities);
            return;
        }

        // 2. Cache lookup
        AsmerCache cache = config.cache();
        Map<K, V> cached = cache.getAll(rule.name(), allKeys);
        metrics[1] = cached.size(); // cacheHits
        List<K> missing = allKeys.stream().filter(k -> !cached.containsKey(k)).toList();

        // 3. Batch-load cache misses
        Map<K, V> loaded = Map.of();
        if (!missing.isEmpty()) {
            Map<K, V> raw;
            try {
                raw = rule.loader().apply(missing);
            } catch (Exception e) {
                throw new AssemblyException(
                        "Loader failed for rule '" + rule.name() + "': " + e.getMessage(), e);
            }
            loaded = raw != null ? raw : Map.of();

            // 4. Write back to cache
            if (!loaded.isEmpty()) {
                cache.putAll(rule.name(), loaded);
            }
        }

        // 5. Merge cached + loaded
        Map<K, V> results;
        if (cached.isEmpty()) {
            results = loaded;
        } else if (loaded.isEmpty()) {
            results = cached;
        } else {
            results = new HashMap<>(cached.size() + loaded.size());
            results.putAll(cached);
            results.putAll(loaded);
        }

        // 6. Set values onto entities
        for (T entity : entities) {
            K key = extractKeySafe(rule, entity);
            V value = (key != null) ? results.get(key) : null;

            if (value == null) {
                value = rule.defaultValue(); // empty list for MANY, null for ONE
            }

            if (value != null) {
                setValueSafe(rule, entity, value);
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private <T, K, V> List<K> collectKeys(Rule<T, K, V> rule, List<T> entities) {
        // LinkedHashSet for O(1) deduplication while preserving insertion order
        LinkedHashSet<K> seen = new LinkedHashSet<>(entities.size());
        for (T entity : entities) {
            K key = extractKeySafe(rule, entity);
            if (key != null) seen.add(key);
        }
        return new ArrayList<>(seen);
    }

    private <T, K, V> K extractKeySafe(Rule<T, K, V> rule, T entity) {
        try {
            return rule.keyExtractor().apply(entity);
        } catch (Exception e) {
            throw new AssemblyException(
                    "Key extraction failed for rule '" + rule.name() + "': " + e.getMessage(), e);
        }
    }

    private <T, K, V> void setValueSafe(Rule<T, K, V> rule, T entity, V value) {
        try {
            rule.setter().accept(entity, value);
        } catch (Exception e) {
            throw new AssemblyException(
                    "Setting value failed for rule '" + rule.name() + "': " + e.getMessage(), e);
        }
    }

    private <T, K, V> void applyDefault(Rule<T, K, V> rule, List<T> entities) {
        V def = rule.defaultValue();
        if (def != null) { // only MANY gets a non-null default (empty list)
            for (T entity : entities) {
                setValueSafe(rule, entity, def);
            }
        }
    }

    private <T, K, V> void handleError(Rule<T, K, V> rule, List<T> entities, AssemblyException e) {
        switch (config.errorPolicy()) {
            case THROW -> throw e;
            case LOG_AND_SKIP ->
                    log.warn("[asmer] rule '{}' failed, skipping: {}", rule.name(), e.getMessage(), e);
            case FALLBACK_EMPTY -> {
                log.warn("[asmer] rule '{}' failed, applying fallback: {}", rule.name(), e.getMessage(), e);
                applyDefault(rule, entities);
            }
        }
    }
}
