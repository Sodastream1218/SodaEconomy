package de.sodaeconomy.integration;

/** Inert integration used when an optional dependency is absent or disabled. */
public enum NoopIntegration implements OptionalIntegration {
    INSTANCE;

    @Override public boolean isActive() { return false; }
    @Override public void close() { }
}
