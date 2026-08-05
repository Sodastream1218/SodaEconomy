package de.sodaeconomy.storage;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Validates storage settings before a storage implementation is created or connected. */
public final class StorageConfigurationValidator {

    public ValidationResult validate(StorageType type, FileConfiguration configuration) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(configuration, "configuration");
        if (type != StorageType.MYSQL) return ValidationResult.successful();

        List<String> problems = new ArrayList<>();
        requireText(configuration, "storage.mysql.host", "Host", problems);
        requirePort(configuration, problems);
        requireText(configuration, "storage.mysql.database", "Datenbank", problems);
        requireText(configuration, "storage.mysql.user", "Benutzername", problems);
        requireText(configuration, "storage.mysql.password", "Passwort", problems);
        return problems.isEmpty() ? ValidationResult.successful() : ValidationResult.failed(problems);
    }

    private void requireText(FileConfiguration configuration, String path, String displayName, List<String> problems) {
        if (!configuration.contains(path, true) || !configuration.isString(path)) {
            problems.add(displayName + " fehlt.");
            return;
        }
        String value = configuration.getString(path);
        if (value == null || value.isBlank()) problems.add(displayName + " fehlt oder ist leer.");
    }

    private void requirePort(FileConfiguration configuration, List<String> problems) {
        String path = "storage.mysql.port";
        if (!configuration.contains(path, true) || !configuration.isInt(path)) {
            problems.add("Port fehlt oder ist keine ganze Zahl.");
            return;
        }
        int port = configuration.getInt(path);
        if (port < 1 || port > 65_535) problems.add("Port muss zwischen 1 und 65535 liegen.");
    }

    public record ValidationResult(boolean valid, List<String> problems) {
        public ValidationResult {
            problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
            if (valid && !problems.isEmpty()) throw new IllegalArgumentException("Valid results cannot contain problems");
            if (!valid && problems.isEmpty()) throw new IllegalArgumentException("Invalid results need at least one problem");
        }

        public static ValidationResult successful() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failed(List<String> problems) {
            return new ValidationResult(false, problems);
        }
    }
}
