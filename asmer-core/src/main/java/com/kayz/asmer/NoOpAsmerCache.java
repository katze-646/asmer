package com.kayz.asmer;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** Package-private singleton returned by {@link AsmerCache#none()}. */
final class NoOpAsmerCache implements AsmerCache {

    static final NoOpAsmerCache INSTANCE = new NoOpAsmerCache();

    private NoOpAsmerCache() {}

    @Override
    public <K, V> Optional<V> get(String namespace, K key) {
        return Optional.empty();
    }

    @Override
    public <K, V> void put(String namespace, K key, V value) {}

    @Override
    public <K, V> Map<K, V> getAll(String namespace, Collection<K> keys) {
        return Map.of();
    }

    @Override
    public <K, V> void putAll(String namespace, Map<K, V> entries) {}

    @Override
    public String toString() {
        return "AsmerCache.none()";
    }
}
