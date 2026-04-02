package com.kayz.asmer;

/**
 * Thrown when an assembly rule is misconfigured — always a programming error.
 * <p>
 * This exception is <strong>never</strong> suppressed by {@link ErrorPolicy};
 * it always propagates to the caller regardless of configuration.
 */
public final class RuleDefinitionException extends AsmerException {

    public RuleDefinitionException(String message) {
        super(message);
    }

    public RuleDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
