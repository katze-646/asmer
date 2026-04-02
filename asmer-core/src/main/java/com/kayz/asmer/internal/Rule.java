package com.kayz.asmer.internal;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Internal representation of a single assembly rule.
 *
 * <p>For {@link RuleKind#MANY}, {@code V} is {@code List<ChildType>} — the loader
 * already returns a grouped map from the framework's wrapping logic.
 *
 * @param <T> the parent entity type
 * @param <K> the key type
 * @param <V> the value type ({@code SomeEntity} for ONE, {@code List<SomeChild>} for MANY)
 */
public record Rule<T, K, V>(
        String name,
        Function<T, K> keyExtractor,
        Function<Collection<K>, Map<K, V>> loader,
        BiConsumer<T, V> setter,
        RuleKind kind
) {
    /** Returns the default value to set when a key is missing or null. */
    @SuppressWarnings("unchecked")
    public V defaultValue() {
        return kind == RuleKind.MANY ? (V) java.util.List.of() : null;
    }
}
