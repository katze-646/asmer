package com.kayz.asmer;

/**
 * Thrown when assembly fails at runtime — e.g. the loader throws, the cache
 * is unreachable, or a setter raises an exception.
 * <p>
 * Behaviour depends on the configured {@link ErrorPolicy}:
 * <ul>
 *   <li>{@link ErrorPolicy#THROW} — propagates immediately (default)</li>
 *   <li>{@link ErrorPolicy#LOG_AND_SKIP} — logged, field left as-is</li>
 *   <li>{@link ErrorPolicy#FALLBACK_EMPTY} — logged, collection field set to empty list</li>
 * </ul>
 */
public final class AssemblyException extends AsmerException {

    public AssemblyException(String message) {
        super(message);
    }

    public AssemblyException(String message, Throwable cause) {
        super(message, cause);
    }
}
