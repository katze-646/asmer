package com.kayz.asmer.internal;

import com.kayz.asmer.AssemblyException;
import com.kayz.asmer.RuleDefinitionException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Reflection helpers used internally during rule construction.
 * All exceptions are converted to {@link RuleDefinitionException}.
 */
public final class Reflections {

    private Reflections() {}

    /**
     * Finds a declared field by name, walking up the class hierarchy.
     *
     * @throws RuleDefinitionException if no such field exists
     */
    public static Field findField(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
        }
        throw new RuleDefinitionException(
                "No field '" + fieldName + "' on " + type.getSimpleName() + " or its superclasses");
    }

    /**
     * Creates a key-extractor {@link Function} by reflectively invoking
     * the getter derived from {@code keyFieldName} (e.g. "userId" → "getUserId").
     */
    @SuppressWarnings("unchecked")
    public static <T, K> Function<T, K> keyExtractor(Class<T> type, String keyFieldName) {
        String getterName = "get" +
                Character.toUpperCase(keyFieldName.charAt(0)) + keyFieldName.substring(1);
        Method getter = findGetter(type, getterName);
        getter.setAccessible(true);
        return entity -> {
            try {
                return (K) getter.invoke(entity);
            } catch (Exception e) {
                throw new AssemblyException(
                        "Key extraction failed via '" + getterName + "': " + e.getMessage(), e);
            }
        };
    }

    /**
     * Creates a setter {@link BiConsumer} by reflectively invoking the setter
     * derived from the getter name (e.g. "getUser" → "setUser").
     */
    @SuppressWarnings("unchecked")
    public static <T, V> BiConsumer<T, V> setter(Class<T> type, String getterName) {
        String setterName = LambdaIntrospector.toSetterName(getterName);
        Method setter = findSetter(type, setterName);
        setter.setAccessible(true);
        return (entity, value) -> {
            try {
                setter.invoke(entity, value);
            } catch (Exception e) {
                throw new AssemblyException(
                        "Setting value failed via '" + setterName + "': " + e.getMessage(), e);
            }
        };
    }

    // ---- private helpers ------------------------------------------------

    private static Method findGetter(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {}
        }
        throw new RuleDefinitionException(
                "No getter '" + name + "()' on " + type.getSimpleName() + " or its superclasses");
    }

    private static Method findSetter(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1) {
                    return m;
                }
            }
        }
        throw new RuleDefinitionException(
                "No setter '" + name + "(...)' on " + type.getSimpleName() + " or its superclasses");
    }
}
