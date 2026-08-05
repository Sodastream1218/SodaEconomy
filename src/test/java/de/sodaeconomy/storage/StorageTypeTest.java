package de.sodaeconomy.storage;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StorageTypeTest {

    @Test
    void resolvesStorageTypesCaseInsensitively() {
        assertEquals(StorageType.SQLITE, StorageType.fromString("sqlite"));
        assertEquals(StorageType.MYSQL, StorageType.fromString("MySql"));
        assertEquals(StorageType.YAML, StorageType.fromString("YAML"));
    }

    @Test
    void fallsBackToSqliteForMissingOrInvalidValues() {
        assertEquals(StorageType.SQLITE, StorageType.fromString(null));
        assertEquals(StorageType.SQLITE, StorageType.fromString("unknown"));
    }

    @Test
    void resolvesTypesIndependentlyOfTheJvmDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(StorageType.SQLITE, StorageType.fromString("sqlite"));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }
}
