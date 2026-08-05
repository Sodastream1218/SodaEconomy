package de.sodaeconomy.storage;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Objects;

/**
 * Starts a verified storage target and performs migrations only after that target is ready.
 * The marker remains authoritative and is updated only after a complete successful transition.
 */
public class StorageManager {

    private final JavaPlugin plugin;
    private final StorageConfigurationValidator configurationValidator = new StorageConfigurationValidator();
    private final StorageMigrationManager migrationManager;
    private Storage current;
    private StorageType currentType;

    public StorageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        migrationManager = new StorageMigrationManager(plugin);
    }

    /**
     * Initialization order: validate target configuration, initialize and verify target, migrate
     * only after that, atomically update the marker, and finally expose the active storage.
     */
    public void init(StorageType desiredType) throws StorageStartupException {
        Objects.requireNonNull(desiredType, "desiredType");
        StorageConfigurationValidator.ValidationResult validation = configurationValidator.validate(desiredType, plugin.getConfig());
        LastStorageState lastState = readLastStorageState();
        if (lastState.status() == MarkerStatus.INVALID) {
            throw new StorageStartupException(StorageStartupException.Kind.STATE_PERSISTENCE_FAILED,
                    "Der Storage-Status konnte nicht sicher gelesen werden. Bitte prüfe last-storage-type.txt. "
                            + "Es wurden keine Daten migriert oder überschrieben.");
        }

        StorageType lastType = lastState.type();
        plugin.getLogger().info("[Storage] Last successfully used storage: "
                + (lastType == null ? "none (first installation)" : lastType));
        plugin.getLogger().info("[Storage] Requested storage: " + desiredType);

        if (!validation.valid()) {
            handleInvalidTargetConfiguration(desiredType, lastType, validation);
            return;
        }

        Storage target = initializeTarget(desiredType);
        ExactStorageSnapshot targetSnapshot = null;
        boolean migrationImportAttempted = false;
        StorageMaintenanceCoordinator.MaintenanceLease maintenanceLease = null;
        try {
            if (lastType == null) {
                activate(target, desiredType, true);
                target = null;
                plugin.getLogger().info("[Storage] No previous storage marker exists. Plugin started with " + desiredType + ".");
                return;
            }

            if (lastType == desiredType) {
                activate(target, desiredType, false);
                target = null;
                plugin.getLogger().info("[Storage] Plugin started with " + desiredType + ".");
                return;
            }

            if (!sourceDataExists(lastType)) {
                activate(target, desiredType, true);
                target = null;
                plugin.getLogger().warning("[Storage] The previous " + lastType + " marker has no local data source. "
                        + "No migration was performed; plugin started with " + desiredType + ".");
                return;
            }

            maintenanceLease = acquireMaintenanceLeaseIfSupported(target, desiredType, lastType, desiredType);
            targetSnapshot = migrationManager.readSnapshot(target, desiredType);
            if (!targetSnapshot.isEmpty()) {
                throw migrationFailure(lastType, desiredType,
                        "Das Zielsystem enthält bereits Daten. Zur Vermeidung von Datenvermischung wurde nicht migriert.", null);
            }

            Storage source = null;
            try {
                source = initializeSource(lastType, desiredType);
                if (maintenanceLease == null) {
                    maintenanceLease = acquireMaintenanceLeaseIfSupported(source, lastType, lastType, desiredType);
                }
                enforceMySqlToLocalMigrationConfirmation(lastType, desiredType);
                ExactStorageSnapshot sourceSnapshot = migrationManager.readSnapshot(source, lastType);
                if (isMigrationDryRunEnabled()) {
                    logMigrationDryRun(lastType, desiredType, sourceSnapshot);
                    closeMaintenanceLease(maintenanceLease);
                    maintenanceLease = null;
                    closeQuietly(target);
                    target = null;
                    activate(source, lastType, false);
                    source = null;
                    plugin.getLogger().warning("[Storage] Migration dry-run completed. No data was imported and "
                            + "last-storage-type.txt was not changed; the plugin continues with " + lastType + ".");
                    return;
                }
                if (!sourceSnapshot.isEmpty()) {
                    plugin.getLogger().info("[Storage] Migration from " + lastType + " to " + desiredType + " started.");
                    // Set this before calling the storage implementation. If a faulty backend
                    // changes data and then reports an error, the original target snapshot is
                    // still restored before the uncommitted target is closed.
                    migrationImportAttempted = true;
                    migrationManager.importSnapshot(target, sourceSnapshot, desiredType);
                    plugin.getLogger().info("[Storage] Migration completed successfully.");
                } else {
                    plugin.getLogger().info("[Storage] Previous storage contains no balances. No data transfer is required.");
                }
            } finally {
                closeQuietly(source);
            }

            closeMaintenanceLease(maintenanceLease);
            maintenanceLease = null;
            activate(target, desiredType, true);
            target = null;
            plugin.getLogger().info("[Storage] Storage successfully switched to " + desiredType + ".");
        } catch (StorageStartupException exception) {
            if (migrationImportAttempted) rollbackImportedTarget(target, targetSnapshot, exception);
            releaseMaintenanceLeaseAfterFailure(maintenanceLease, exception);
            closeQuietly(target);
            throw exception;
        } catch (Exception exception) {
            StorageStartupException failure = migrationFailure(lastType, desiredType, null, exception);
            if (migrationImportAttempted) rollbackImportedTarget(target, targetSnapshot, failure);
            releaseMaintenanceLeaseAfterFailure(maintenanceLease, failure);
            closeQuietly(target);
            throw failure;
        }
    }


    private void enforceMySqlToLocalMigrationConfirmation(StorageType sourceType, StorageType targetType)
            throws StorageStartupException {
        if (sourceType != StorageType.MYSQL || !isLocalStorage(targetType)) {
            return;
        }
        if (plugin.getConfig().getBoolean("storage.migration.confirm-single-server-mysql", false)) {
            return;
        }
        throw new StorageStartupException(StorageStartupException.Kind.MIGRATION_FAILED,
                "Die Migration von MYSQL nach " + targetType + " wurde aus Sicherheitsgründen abgebrochen.\n"
                        + "Eine MySQL-Datenbank kann von mehreren Paper-/Purpur-Servern gleichzeitig genutzt werden. "
                        + "Wenn dieser Server jetzt auf einen lokalen Storage migriert, schreiben andere Server "
                        + "möglicherweise weiterhin in MySQL; der lokale Snapshot wäre sofort veraltet und spätere "
                        + "Änderungen könnten verloren gehen.\n"
                        + "Setze storage.migration.confirm-single-server-mysql nur dann auf true, wenn wirklich nur "
                        + "dieser eine Server die MySQL-Datenbank verwendet und alle anderen Instanzen gestoppt sind.");
    }

    private boolean isMigrationDryRunEnabled() {
        return plugin.getConfig().getBoolean("storage.migration.dry-run", false);
    }

    private boolean isLocalStorage(StorageType type) {
        return type == StorageType.YAML || type == StorageType.SQLITE;
    }

    private void logMigrationDryRun(StorageType sourceType, StorageType targetType, ExactStorageSnapshot snapshot) {
        plugin.getLogger().warning("[Storage] Migration dry-run from " + sourceType + " to " + targetType
                + " read " + snapshot.balances().size() + " wallet balance(s), "
                + snapshot.bankBalances().size() + " bank balance(s), and "
                + snapshot.walletTransactions().size() + " wallet transaction(s). No target data will be changed.");
    }

    private StorageMaintenanceCoordinator.MaintenanceLease acquireMaintenanceLeaseIfSupported(
            Storage storage, StorageType storageType, StorageType sourceType, StorageType targetType) throws Exception {
        if (storageType != StorageType.MYSQL) {
            return null;
        }
        if (storage instanceof StorageMaintenanceCoordinator coordinator) {
            return coordinator.acquireMaintenanceLease("storage migration from " + sourceType + " to " + targetType);
        }
        return null;
    }

    private void closeMaintenanceLease(StorageMaintenanceCoordinator.MaintenanceLease lease)
            throws StorageStartupException {
        if (lease == null) return;
        try {
            lease.close();
        } catch (Exception exception) {
            throw new StorageStartupException(StorageStartupException.Kind.STATE_PERSISTENCE_FAILED,
                    "The shared MySQL maintenance gate could not be released safely", exception);
        }
    }


    private void releaseMaintenanceLeaseAfterFailure(StorageMaintenanceCoordinator.MaintenanceLease lease,
                                                     StorageStartupException failure) {
        if (lease == null) return;
        try {
            lease.close();
        } catch (Exception releaseFailure) {
            failure.addSuppressed(releaseFailure);
            plugin.getLogger().severe("[Storage] The shared MySQL maintenance gate could not be released after a "
                    + "failed migration. Wallet mutations remain blocked until the database state is repaired.");
        }
    }

    private void rollbackImportedTarget(Storage target, ExactStorageSnapshot originalSnapshot, StorageStartupException failure) {
        try {
            target.replaceMinorUnitSnapshot(originalSnapshot.balances(), originalSnapshot.bankBalances(),
                    originalSnapshot.walletTransactions(), originalSnapshot.playerIdentities());
            plugin.getLogger().warning("[Storage] Migration target was restored because the storage switch was not committed.");
        } catch (Exception rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            plugin.getLogger().severe("[Storage] Migration target could not be restored after an uncommitted migration. "
                    + "Do not restart with a different storage type before resolving the logged error.");
        }
    }

    private void handleInvalidTargetConfiguration(StorageType desiredType, StorageType lastType,
                                                  StorageConfigurationValidator.ValidationResult validation)
            throws StorageStartupException {
        String problemMessage = configurationProblemMessage(validation);
        plugin.getLogger().warning("[Storage] " + problemMessage.replace("\n", " "));

        if (desiredType != StorageType.MYSQL || lastType == null || lastType == StorageType.MYSQL
                || !sourceDataExists(lastType)) {
            throw new StorageStartupException(StorageStartupException.Kind.INVALID_CONFIGURATION, problemMessage);
        }

        plugin.getLogger().warning("[Storage] Automatic fallback to the last successfully used storage " + lastType + " started.");
        Storage fallback = null;
        try {
            fallback = initializeStorage(lastType, "Fallback");
            persistConfiguredStorageType(lastType);
            current = fallback;
            currentType = lastType;
            fallback = null;
            plugin.getLogger().warning("[Storage] MySQL configuration is invalid. Config was changed to " + lastType
                    + " and the plugin started with the verified fallback storage.");
        } catch (StorageStartupException exception) {
            closeQuietly(fallback);
            throw exception;
        } catch (Exception exception) {
            closeQuietly(fallback);
            throw new StorageStartupException(StorageStartupException.Kind.STATE_PERSISTENCE_FAILED,
                    "Die MySQL-Konfiguration ist ungültig und der sichere Fallback auf " + lastType
                            + " konnte nicht vollständig übernommen werden. Die Konfiguration wurde nicht geändert.", exception);
        }
    }

    /**
     * Does not use a runtime fallback for a reachable-but-failing target. The storages are
     * independent data sets, so accepting writes in the old storage during this server run would
     * create balances that cannot be reconciled safely when the requested target recovers.
     */
    private Storage initializeTarget(StorageType targetType) throws StorageStartupException {
        try {
            Storage target = initializeStorage(targetType, "Target");
            plugin.getLogger().info("[Storage] " + targetType + " initialized and connection/schema verification succeeded.");
            return target;
        } catch (StorageStartupException exception) {
            throw exception;
        } catch (Exception exception) {
            String message = targetType == StorageType.MYSQL
                    ? "Die MySQL-Konfiguration ist gültig, aber MySQL ist derzeit nicht erreichbar oder konnte nicht initialisiert werden. "
                    + "Es wurde kein Fallback verwendet, keine Migration durchgeführt und weder config.yml noch der Storage-Status geändert. "
                    + "Bitte prüfe Erreichbarkeit, Netzwerk, Zugangsdaten und Berechtigungen."
                    : "Das ausgewählte Storage-System " + targetType + " konnte nicht initialisiert werden. "
                    + "Es wurden keine Daten migriert und kein Storage-Status geändert.";
            throw new StorageStartupException(StorageStartupException.Kind.TARGET_UNAVAILABLE, message, exception);
        }
    }

    private Storage initializeSource(StorageType sourceType, StorageType targetType) throws StorageStartupException {
        try {
            return initializeStorage(sourceType, "Source");
        } catch (Exception exception) {
            throw migrationFailure(sourceType, targetType,
                    "Die bisherige Datenquelle konnte nicht geöffnet werden.", exception);
        }
    }

    private Storage initializeStorage(StorageType type, String role) throws Exception {
        Storage storage = createStorageInstance(type);
        try {
            storage.init(plugin);
            plugin.getLogger().info("[Storage] " + role + " storage " + type + " initialized.");
            return storage;
        } catch (Exception exception) {
            closeQuietly(storage);
            throw exception;
        }
    }

    private void activate(Storage storage, StorageType type, boolean updateMarker) throws StorageStartupException {
        if (updateMarker) {
            try {
                writeLastType(type);
            } catch (IOException exception) {
                throw new StorageStartupException(StorageStartupException.Kind.STATE_PERSISTENCE_FAILED,
                        "Der Storage-Wechsel konnte nicht sicher abgeschlossen werden, weil last-storage-type.txt nicht gespeichert werden konnte. "
                                + "Es wurden keine neuen Daten als aktiv übernommen.", exception);
            }
        }
        current = storage;
        currentType = type;
    }

    private StorageStartupException migrationFailure(StorageType sourceType, StorageType targetType,
                                                      String detail, Throwable cause) {
        String message = "Die Migration von " + sourceType + " nach " + targetType + " wurde abgebrochen. "
                + "Der Storage-Status und die Konfiguration wurden nicht geändert; die Quelldaten bleiben unverändert."
                + (detail == null ? "" : " " + detail);
        return cause == null
                ? new StorageStartupException(StorageStartupException.Kind.MIGRATION_FAILED, message)
                : new StorageStartupException(StorageStartupException.Kind.MIGRATION_FAILED, message, cause);
    }

    private String configurationProblemMessage(StorageConfigurationValidator.ValidationResult validation) {
        StringBuilder message = new StringBuilder("MySQL wurde als Storage ausgewählt, jedoch sind die Verbindungsdaten unvollständig oder ungültig.\n"
                + "Bitte überprüfe:");
        for (String problem : validation.problems()) message.append("\n- ").append(problem);
        return message.toString();
    }

    public Storage createStorageInstance(StorageType type) {
        return switch (type) {
            case MYSQL -> new MySQLStorage();
            case YAML -> new YamlStorage();
            case SQLITE -> new SQLiteStorage();
        };
    }

    /**
     * Legacy low-level batch persistence access. Not part of the supported public API; use
     * EconomyTransactionApi instead so journal and balance state remain consistent.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public void saveAll(Map<UUID, Double> data) throws Exception {
        StorageAccessGuard.requireInternalCaller("StorageManager.saveAll", StorageManager.class);
        if (current != null) current.saveAll(data);
    }

    /**
     * Legacy internal storage accessor. Not part of the supported public API; returned storage
     * instances reject external low-level mutations at runtime.
     */
    @Deprecated(since = "1.1", forRemoval = true)
    public Storage getCurrent() {
        return current;
    }

    public StorageType getCurrentType() {
        return currentType;
    }

    public void close() {
        closeQuietly(current);
        current = null;
        currentType = null;
    }

    private boolean sourceDataExists(StorageType type) {
        File storage = new File(plugin.getDataFolder(), "storage");
        return switch (type) {
            case SQLITE -> new File(storage, "economy.db").isFile();
            case YAML -> new File(storage, "balances.yml").isFile();
            // A previous MySQL source can only be verified by its later, explicit initialization.
            case MYSQL -> true;
        };
    }

    private LastStorageState readLastStorageState() {
        File marker = lastTypeFile();
        if (!marker.isFile()) return new LastStorageState(MarkerStatus.MISSING, null);
        try {
            String content = Files.readString(marker.toPath()).trim();
            Optional<StorageType> type = StorageType.parse(content);
            if (type.isPresent()) return new LastStorageState(MarkerStatus.VALID, type.get());
            plugin.getLogger().warning("[Storage] last-storage-type.txt contains an unknown storage type.");
        } catch (IOException exception) {
            plugin.getLogger().warning("[Storage] Could not read last-storage-type.txt: " + exception.getMessage());
        }
        return new LastStorageState(MarkerStatus.INVALID, null);
    }

    private void writeLastType(StorageType type) throws IOException {
        Path marker = lastTypeFile().toPath();
        Files.createDirectories(marker.getParent());
        Path temporary = Files.createTempFile(marker.getParent(), "last-storage-type-", ".tmp");
        try {
            Files.writeString(temporary, type.name());
            moveAtomically(temporary, marker);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void persistConfiguredStorageType(StorageType type) throws IOException {
        String path = "storage.type";
        Object previousValue = plugin.getConfig().get(path);
        Path configuration = new File(plugin.getDataFolder(), "config.yml").toPath();
        Files.createDirectories(configuration.getParent());
        Path temporary = Files.createTempFile(configuration.getParent(), "config-", ".tmp");
        plugin.getConfig().set(path, type.name());
        try {
            plugin.getConfig().save(temporary.toFile());
            moveAtomically(temporary, configuration);
        } catch (IOException exception) {
            plugin.getConfig().set(path, previousValue);
            throw exception;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private File lastTypeFile() {
        return new File(plugin.getDataFolder(), "last-storage-type.txt");
    }

    private void closeQuietly(Storage storage) {
        if (storage == null) return;
        try {
            storage.close();
        } catch (Exception ignored) {
            // Closing a failed, not-yet-active storage must not hide the original startup failure.
        }
    }

    private enum MarkerStatus {
        MISSING,
        VALID,
        INVALID
    }

    private record LastStorageState(MarkerStatus status, StorageType type) { }
}
