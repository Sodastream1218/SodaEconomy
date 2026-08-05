package de.sodaeconomy.storage;

/** Thrown before a local state mutation while local persistence is paused or shutting down. */
public final class PersistenceUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PersistenceUnavailableException(String message) {
        super(message);
    }

    public PersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
