package de.sodaeconomy.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigurationValidatorTest {
    private final StorageConfigurationValidator validator = new StorageConfigurationValidator();

    @Test
    void acceptsACompleteMySqlConfiguration() {
        assertTrue(validator.validate(StorageType.MYSQL, completeMySqlConfiguration()).valid());
    }

    @Test
    void rejectsAMissingMySqlHostBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.host", null);

        assertProblem(configuration, "Host");
    }

    @Test
    void rejectsAnOutOfRangeMySqlPortBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.port", 65_536);

        assertProblem(configuration, "Port");
    }

    @Test
    void rejectsANonNumericMySqlPortBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.port", "not-a-port");

        assertProblem(configuration, "Port");
    }

    @Test
    void rejectsAMissingMySqlDatabaseBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.database", " ");

        assertProblem(configuration, "Database");
    }

    @Test
    void rejectsAMissingMySqlUserBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.user", "");

        assertProblem(configuration, "Username");
    }

    @Test
    void rejectsAMissingMySqlPasswordBeforeConnection() {
        YamlConfiguration configuration = completeMySqlConfiguration();
        configuration.set("storage.mysql.password", null);

        assertProblem(configuration, "Password");
    }

    @Test
    void doesNotRequireMySqlSettingsForLocalStorage() {
        assertTrue(validator.validate(StorageType.SQLITE, new YamlConfiguration()).valid());
        assertTrue(validator.validate(StorageType.YAML, new YamlConfiguration()).valid());
    }

    private void assertProblem(YamlConfiguration configuration, String expectedProblem) {
        StorageConfigurationValidator.ValidationResult result = validator.validate(StorageType.MYSQL, configuration);

        assertFalse(result.valid());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains(expectedProblem)));
    }

    private YamlConfiguration completeMySqlConfiguration() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("storage.mysql.host", "127.0.0.1");
        configuration.set("storage.mysql.port", 3306);
        configuration.set("storage.mysql.database", "sodaeconomy_test");
        configuration.set("storage.mysql.user", "test_user");
        configuration.set("storage.mysql.password", "test_password");
        return configuration;
    }
}
