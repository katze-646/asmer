package com.kayz.asmer;

/**
 * Base unchecked exception for the Asmer framework.
 */
public class AsmerException extends RuntimeException {

    public AsmerException(String message) {
        super(message);
    }

    public AsmerException(String message, Throwable cause) {
        super(message, cause);
    }
}
