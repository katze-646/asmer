package com.kayz.asmer.internal;

import com.kayz.asmer.RuleDefinitionException;

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * Extracts implementation method information from a {@link SerializableGetter}.
 * Only unbound method references (e.g. {@code Order::getUser}) are supported.
 */
public final class LambdaIntrospector {

    private LambdaIntrospector() {}

    /**
     * Returns the implementation method name of the given getter reference.
     * <p>For {@code Order::getUser} this returns {@code "getUser"}.
     *
     * @throws RuleDefinitionException if introspection fails (e.g. lambda expression passed instead of method reference)
     */
    public static String methodName(SerializableGetter<?, ?> getter) {
        try {
            Method writeReplace = getter.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            SerializedLambda sl = (SerializedLambda) writeReplace.invoke(getter);
            return sl.getImplMethodName();
        } catch (Exception e) {
            throw new RuleDefinitionException(
                    "Cannot introspect getter — pass an unbound method reference (e.g. Order::getUser), " +
                    "not a lambda expression: " + e.getMessage());
        }
    }

    /**
     * Converts a getter method name to the corresponding field name.
     * <p>{@code "getUser"} → {@code "user"}, {@code "isActive"} → {@code "active"}.
     *
     * @throws RuleDefinitionException if the name does not follow getter conventions
     */
    public static String toFieldName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return Character.toLowerCase(methodName.charAt(2)) + methodName.substring(3);
        }
        throw new RuleDefinitionException(
                "Getter '" + methodName + "' does not follow naming convention (getXxx / isXxx)");
    }

    /**
     * Converts a getter method name to the derived setter name.
     * <p>{@code "getUser"} → {@code "setUser"}, {@code "isActive"} → {@code "setActive"}.
     */
    public static String toSetterName(String getterName) {
        if (getterName.startsWith("get") && getterName.length() > 3) {
            return "set" + getterName.substring(3);
        }
        if (getterName.startsWith("is") && getterName.length() > 2) {
            return "set" + Character.toUpperCase(getterName.charAt(2)) + getterName.substring(3);
        }
        throw new RuleDefinitionException(
                "Cannot derive setter from getter '" + getterName + "'");
    }
}
