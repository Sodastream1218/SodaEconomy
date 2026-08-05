package de.sodaeconomy.storage;

/** Thrown before a local state mutation when the bounded persistence queue cannot accept it. */
public final class PersistenceQueueFullException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public PersistenceQueueFullException(String message) {
        super(message);
    }
}
