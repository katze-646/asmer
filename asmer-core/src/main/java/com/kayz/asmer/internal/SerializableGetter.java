package com.kayz.asmer.internal;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A {@link Function} that is also {@link Serializable}, enabling lambda
 * introspection via {@link java.lang.invoke.SerializedLambda}.
 *
 * <p>Pass unbound method references (e.g. {@code Order::getUser}) wherever
 * this type is expected; the compiler generates a serializable lambda automatically.
 *
 * @param <T> the entity type
 * @param <R> the return type of the getter
 */
@FunctionalInterface
public interface SerializableGetter<T, R> extends Function<T, R>, Serializable {
}
