package de.sodaeconomy.storage;

import java.util.Locale;
import java.util.Optional;

public enum StorageType {
    SQLITE,
    YAML,
    MYSQL;

    public static StorageType fromString(String s) {
        return parse(s).orElse(SQLITE);
    }

    /** Strict parser for persisted storage state; unknown values must not silently become SQLite. */
    public static Optional<StorageType> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(StorageType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
