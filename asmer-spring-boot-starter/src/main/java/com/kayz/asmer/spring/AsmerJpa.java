package com.kayz.asmer.spring;

import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * Spring Data integration helpers for Asmer.
 *
 * <p>Eliminates boilerplate fetch-and-convert code. Example:
 *
 * <pre>{@code
 * // Many-to-one (uses CrudRepository.findAllById — no custom repo method needed)
 * Asmer.of(orders)
 *     .on(Order::getUser, AsmerJpa.byId(userRepo), User::getId)
 *     .assemble();
 *
 * // One-to-many (pass repo method directly; grouping by keyMapper is handled by Asmer)
 * Asmer.of(orders)
 *     .on(Order::getItems, itemRepo::findByOrderIdIn, OrderItem::getOrderId)
 *     .assemble();
 * }</pre>
 */
public final class AsmerJpa {

    private AsmerJpa() {}

    /**
     * Wraps a {@link CrudRepository} into a batch fetcher compatible with
     * {@link com.kayz.asmer.Asmer#on Asmer.on()}.
     *
     * <p>Uses the standard {@code findAllById} — no custom repository method needed.
     *
     * @param repository any Spring Data repository
     */
    public static <K, V> Function<Collection<K>, List<V>> byId(CrudRepository<V, K> repository) {
        return keys -> StreamSupport
                .stream(repository.findAllById(keys).spliterator(), false)
                .toList();
    }
}
