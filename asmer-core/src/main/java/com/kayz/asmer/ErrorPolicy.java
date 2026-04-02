package com.kayz.asmer;

/**
 * Defines how the assembly engine reacts when a rule fails at runtime.
 *
 * <p>{@link RuleDefinitionException} (programming errors) are <em>always</em>
 * re-thrown regardless of the policy set here.
 */
public enum ErrorPolicy {

    /**
     * Re-throw the exception immediately. The entire {@code assemble()} call fails.
     * This is the default.
     */
    THROW,

    /**
     * Log a warning and skip the failed rule.
     * The target field is left in its current state (typically {@code null}).
     */
    LOG_AND_SKIP,

    /**
     * Log a warning and apply a safe default:
     * <ul>
     *   <li>{@code @AssembleMany} fields receive an empty {@code List}</li>
     *   <li>{@code @AssembleOne} fields are left {@code null}</li>
     * </ul>
     */
    FALLBACK_EMPTY
}
