package de.sodaeconomy.integration;

/** Lifecycle boundary for an optional third-party integration. */
public interface OptionalIntegration extends AutoCloseable {
    boolean isActive();

    @Override
    void close();
}
