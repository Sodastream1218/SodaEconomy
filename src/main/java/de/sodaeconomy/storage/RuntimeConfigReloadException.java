package de.sodaeconomy.storage;

/** Raised when the safe runtime configuration cannot be read or validated atomically. */
public final class RuntimeConfigReloadException extends Exception {
    public RuntimeConfigReloadException(String message) {
        super(message);
    }

    public RuntimeConfigReloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
