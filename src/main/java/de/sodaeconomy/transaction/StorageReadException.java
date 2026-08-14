package de.sodaeconomy.transaction;

/** Internal signal that persisted economy data could not be read reliably. */
final class StorageReadException extends RuntimeException {
    StorageReadException(String operation, Throwable cause) {
        super("Could not " + operation + ".", cause);
    }
}
