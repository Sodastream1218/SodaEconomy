package de.sodaeconomy.storage;

/**
 * Thrown when code outside SodaEconomy's internal storage or transaction packages attempts to
 * use low-level storage operations that can bypass the supported transaction API.
 */
public class UnauthorizedStorageAccessException extends SecurityException {
    public UnauthorizedStorageAccessException(String message) {
        super(message);
    }
}
