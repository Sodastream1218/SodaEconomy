package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises startup transitions without touching a real database or a production data directory. */
class StorageManagerSafetyTest extends MockBukkitTestBase {
    private final List<String> events = new ArrayList<>();
    private final Map<StorageType, RecordingStorageState> states = new EnumMap<>(StorageType.class);
    private Path dataFolder;
    private StorageManager manager;

    @BeforeEach
    void prepareStorageState() throws Exception {
        plugin.getStorageManager().close();
        dataFolder = plugin.getDataFolder().toPath();
        Files.createDirectories(storageDirectory());
        Files.deleteIfExists(markerPath());
        Files.deleteIfExists(storageDirectory().resolve("balances.yml"));
        Files.deleteIfExists(storageDirectory().resolve("economy.db"));

        for (StorageType type : StorageType.values()) {
            states.put(type, new RecordingStorageState(type, events));
        }
        configureValidMySql();
    }

    @AfterEach
    void restoreSafeConfiguration() {
        if (manager != null) manager.close();
        plugin.getConfig().set("storage.type", "SQLITE");
        plugin.getConfig().set("storage.migration.confirm-single-server-mysql", false);
        plugin.getConfig().set("storage.migration.dry-run", false);
        configureValidMySql();
        plugin.saveConfig();
    }

    @Test
    void fallsBackToTheLastLocalStorageAndPersistsTheConfigWhenMySqlHostIsMissing() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(playerId, 75D);
        states.get(StorageType.YAML).bankBalances.put(playerId, 15D);
        writeLastStorage(StorageType.YAML);
        plugin.getConfig().set("storage.type", "MYSQL");
        plugin.getConfig().set("storage.mysql.host", null);

        manager = new RecordingStorageManager(plugin, states);
        manager.init(StorageType.MYSQL);

        assertEquals(StorageType.YAML, manager.getCurrentType());
        assertEquals(1, states.get(StorageType.YAML).initCalls);
        assertEquals(0, states.get(StorageType.MYSQL).initCalls);
        assertFalse(events.contains("YAML:read-main"));
        assertEquals(75D, manager.getCurrent().getBalance(playerId));
        assertEquals(15D, manager.getCurrent().getBankBalance(playerId));
        assertEquals("YAML", Files.readString(markerPath()).trim());

        plugin.reloadConfig();
        assertEquals("YAML", plugin.getConfig().getString("storage.type"));
    }

    @Test
    void doesNotFallbackOrChangePersistentStateWhenValidMySqlIsUnavailable() throws Exception {
        writeLastStorage(StorageType.YAML);
        states.get(StorageType.MYSQL).initializationFailure = new SQLException("Connection timed out");
        plugin.getConfig().set("storage.type", "MYSQL");
        plugin.saveConfig();

        manager = new RecordingStorageManager(plugin, states);
        StorageStartupException failure = assertThrows(StorageStartupException.class,
                () -> manager.init(StorageType.MYSQL));

        assertEquals(StorageStartupException.Kind.TARGET_UNAVAILABLE, failure.getKind());
        assertNull(manager.getCurrent());
        assertEquals(1, states.get(StorageType.MYSQL).initCalls);
        assertEquals(0, states.get(StorageType.YAML).initCalls);
        assertEquals("YAML", Files.readString(markerPath()).trim());

        plugin.reloadConfig();
        assertEquals("MYSQL", plugin.getConfig().getString("storage.type"));
    }

    @Test
    void initializesAndVerifiesTheTargetBeforeReadingTheSourceAndThenMigratesBothBalanceTypes() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(playerId, 125D);
        states.get(StorageType.YAML).bankBalances.put(playerId, 25D);
        PlayerIdentity identity = PlayerIdentity.of(playerId, "MigratedPlayer",
                Instant.ofEpochMilli(42L), PlayerType.BEDROCK);
        states.get(StorageType.YAML).playerIdentities.put(playerId, identity);
        writeLastStorage(StorageType.YAML);

        manager = new RecordingStorageManager(plugin, states);
        manager.init(StorageType.SQLITE);

        assertEquals(StorageType.SQLITE, manager.getCurrentType());
        assertEquals(Map.of(playerId, 125D), states.get(StorageType.SQLITE).balances);
        assertEquals(Map.of(playerId, 25D), states.get(StorageType.SQLITE).bankBalances);
        assertEquals(Map.of(playerId, identity), states.get(StorageType.SQLITE).playerIdentities);
        assertEquals("SQLITE", Files.readString(markerPath()).trim());
        assertTrue(events.indexOf("SQLITE:init") < events.indexOf("YAML:init"));
        assertTrue(events.indexOf("SQLITE:init") < events.indexOf("YAML:read-main"));
    }

    @Test
    void restoresTheTargetAndKeepsTheOldMarkerWhenAnImportFailsAfterChangingData() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(playerId, 125D);
        states.get(StorageType.YAML).bankBalances.put(playerId, 25D);
        PlayerIdentity identity = PlayerIdentity.of(playerId, "RollbackPlayer",
                Instant.ofEpochMilli(84L), PlayerType.JAVA);
        states.get(StorageType.YAML).playerIdentities.put(playerId, identity);
        states.get(StorageType.SQLITE).failFirstSnapshotReplacementAfterMutation = true;
        writeLastStorage(StorageType.YAML);

        manager = new RecordingStorageManager(plugin, states);
        StorageStartupException failure = assertThrows(StorageStartupException.class,
                () -> manager.init(StorageType.SQLITE));

        assertEquals(StorageStartupException.Kind.MIGRATION_FAILED, failure.getKind());
        assertNull(manager.getCurrent());
        assertTrue(states.get(StorageType.SQLITE).balances.isEmpty());
        assertTrue(states.get(StorageType.SQLITE).bankBalances.isEmpty());
        assertTrue(states.get(StorageType.SQLITE).playerIdentities.isEmpty());
        assertEquals(2, states.get(StorageType.SQLITE).snapshotReplacementCalls);
        assertEquals(Map.of(playerId, 125D), states.get(StorageType.YAML).balances);
        assertEquals(Map.of(playerId, 25D), states.get(StorageType.YAML).bankBalances);
        assertEquals("YAML", Files.readString(markerPath()).trim());
    }

    @Test
    void refusesToMixMigrationDataWithAnExistingTargetDataset() throws Exception {
        UUID sourcePlayer = UUID.randomUUID();
        UUID targetPlayer = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(sourcePlayer, 125D);
        states.get(StorageType.SQLITE).balances.put(targetPlayer, 99D);
        writeLastStorage(StorageType.YAML);

        manager = new RecordingStorageManager(plugin, states);
        StorageStartupException failure = assertThrows(StorageStartupException.class,
                () -> manager.init(StorageType.SQLITE));

        assertEquals(StorageStartupException.Kind.MIGRATION_FAILED, failure.getKind());
        assertEquals(Map.of(targetPlayer, 99D), states.get(StorageType.SQLITE).balances);
        assertEquals(Map.of(sourcePlayer, 125D), states.get(StorageType.YAML).balances);
        assertEquals(0, states.get(StorageType.YAML).initCalls);
        assertEquals("YAML", Files.readString(markerPath()).trim());
    }


    @Test
    void refusesMySqlToLocalMigrationWithoutSingleServerConfirmation() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.MYSQL).balances.put(playerId, 125D);
        states.get(StorageType.MYSQL).bankBalances.put(playerId, 25D);
        states.get(StorageType.MYSQL).maintenanceCapable = true;
        writeLastStorage(StorageType.MYSQL);

        manager = new RecordingStorageManager(plugin, states);
        StorageStartupException failure = assertThrows(StorageStartupException.class,
                () -> manager.init(StorageType.YAML));

        assertEquals(StorageStartupException.Kind.MIGRATION_FAILED, failure.getKind());
        assertTrue(failure.getMessage().contains("Migration from MYSQL to YAML"));
        assertTrue(states.get(StorageType.YAML).balances.isEmpty());
        assertFalse(events.contains("MYSQL:read-main"));
        assertTrue(events.contains("MYSQL:maintenance-release"));
        assertEquals("MYSQL", Files.readString(markerPath()).trim());
        assertNull(manager.getCurrent());
    }

    @Test
    void allowsMySqlToLocalMigrationWithSingleServerConfirmation() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.MYSQL).balances.put(playerId, 125D);
        states.get(StorageType.MYSQL).bankBalances.put(playerId, 25D);
        states.get(StorageType.MYSQL).maintenanceCapable = true;
        plugin.getConfig().set("storage.migration.confirm-single-server-mysql", true);
        writeLastStorage(StorageType.MYSQL);

        manager = new RecordingStorageManager(plugin, states);
        manager.init(StorageType.YAML);

        assertEquals(StorageType.YAML, manager.getCurrentType());
        assertEquals(Map.of(playerId, 125D), states.get(StorageType.YAML).balances);
        assertEquals(Map.of(playerId, 25D), states.get(StorageType.YAML).bankBalances);
        assertEquals("YAML", Files.readString(markerPath()).trim());
        assertTrue(events.indexOf("MYSQL:maintenance-acquire") < events.indexOf("MYSQL:read-main"));
        assertTrue(events.indexOf("YAML:replace-snapshot") < events.indexOf("MYSQL:maintenance-release"));
    }

    @Test
    void dryRunReadsTheSourceSnapshotWithoutImportingOrSwitchingStorage() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.MYSQL).balances.put(playerId, 125D);
        states.get(StorageType.MYSQL).bankBalances.put(playerId, 25D);
        states.get(StorageType.MYSQL).maintenanceCapable = true;
        plugin.getConfig().set("storage.migration.confirm-single-server-mysql", true);
        plugin.getConfig().set("storage.migration.dry-run", true);
        writeLastStorage(StorageType.MYSQL);

        manager = new RecordingStorageManager(plugin, states);
        manager.init(StorageType.YAML);

        assertEquals(StorageType.MYSQL, manager.getCurrentType());
        assertTrue(events.contains("MYSQL:read-main"));
        assertFalse(events.contains("YAML:replace-snapshot"));
        assertTrue(states.get(StorageType.YAML).balances.isEmpty());
        assertEquals("MYSQL", Files.readString(markerPath()).trim());
        assertFalse(states.get(StorageType.MYSQL).maintenanceActive);
    }

    @Test
    void holdsTheMySqlMaintenanceGateAcrossSnapshotImportAndReleasesItBeforeActivation() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(playerId, 125D);
        states.get(StorageType.MYSQL).maintenanceCapable = true;
        writeLastStorage(StorageType.YAML);

        manager = new RecordingStorageManager(plugin, states);
        manager.init(StorageType.MYSQL);

        assertEquals(StorageType.MYSQL, manager.getCurrentType());
        assertTrue(events.indexOf("MYSQL:maintenance-acquire") < events.indexOf("MYSQL:read-main"));
        assertTrue(events.indexOf("MYSQL:maintenance-acquire") < events.indexOf("YAML:read-main"));
        assertTrue(events.indexOf("MYSQL:replace-snapshot") < events.indexOf("MYSQL:maintenance-release"));
        assertFalse(states.get(StorageType.MYSQL).maintenanceActive);
    }

    @Test
    void releasesTheMySqlMaintenanceGateWhenSnapshotImportFails() throws Exception {
        UUID playerId = UUID.randomUUID();
        states.get(StorageType.YAML).balances.put(playerId, 125D);
        states.get(StorageType.MYSQL).maintenanceCapable = true;
        states.get(StorageType.MYSQL).failFirstSnapshotReplacementAfterMutation = true;
        writeLastStorage(StorageType.YAML);

        manager = new RecordingStorageManager(plugin, states);
        StorageStartupException failure = assertThrows(StorageStartupException.class,
                () -> manager.init(StorageType.MYSQL));

        assertEquals(StorageStartupException.Kind.MIGRATION_FAILED, failure.getKind());
        assertTrue(events.contains("MYSQL:maintenance-release"));
        assertFalse(states.get(StorageType.MYSQL).maintenanceActive);
        assertEquals("YAML", Files.readString(markerPath()).trim());
    }

    private void configureValidMySql() {
        plugin.getConfig().set("storage.mysql.host", "127.0.0.1");
        plugin.getConfig().set("storage.mysql.port", 3306);
        plugin.getConfig().set("storage.mysql.database", "sodaeconomy_test");
        plugin.getConfig().set("storage.mysql.user", "test_user");
        plugin.getConfig().set("storage.mysql.password", "test_password");
    }

    private void writeLastStorage(StorageType type) throws IOException {
        Files.writeString(markerPath(), type.name());
        Path source = switch (type) {
            case YAML -> storageDirectory().resolve("balances.yml");
            case SQLITE -> storageDirectory().resolve("economy.db");
            case MYSQL -> null;
        };
        if (source != null) {
            Files.writeString(source, "test source marker");
        }
    }

    private Path storageDirectory() {
        return dataFolder.resolve("storage");
    }

    private Path markerPath() {
        return dataFolder.resolve("last-storage-type.txt");
    }

    private static final class RecordingStorageManager extends StorageManager {
        private final Map<StorageType, RecordingStorageState> states;

        private RecordingStorageManager(JavaPlugin plugin, Map<StorageType, RecordingStorageState> states) {
            super(plugin);
            this.states = states;
        }

        @Override
        public Storage createStorageInstance(StorageType type) {
            return new RecordingStorage(states.get(type));
        }
    }

    private static final class RecordingStorageState {
        private final StorageType type;
        private final List<String> events;
        private final Map<UUID, Double> balances = new HashMap<>();
        private final Map<UUID, Double> bankBalances = new HashMap<>();
        private final Map<UUID, PlayerIdentity> playerIdentities = new HashMap<>();
        private Exception initializationFailure;
        private boolean failFirstSnapshotReplacementAfterMutation;
        private boolean snapshotReplacementFailed;
        private int initCalls;
        private int snapshotReplacementCalls;
        private boolean maintenanceCapable;
        private boolean maintenanceActive;

        private RecordingStorageState(StorageType type, List<String> events) {
            this.type = type;
            this.events = events;
        }
    }

    private static final class RecordingStorage implements Storage, StorageMaintenanceCoordinator {
        private final RecordingStorageState state;

        private RecordingStorage(RecordingStorageState state) {
            this.state = state;
        }

        @Override
        public void init(JavaPlugin plugin) throws Exception {
            state.events.add(state.type + ":init");
            state.initCalls++;
            if (state.initializationFailure != null) throw state.initializationFailure;
        }

        @Override
        public Double getBalance(UUID uuid) {
            return state.balances.get(uuid);
        }

        @Override
        public double getOrCreateBalance(UUID uuid, double initialBalance) {
            return state.balances.computeIfAbsent(uuid, ignored -> Money.normalize(initialBalance));
        }

        @Override
        public void setBalance(UUID uuid, double amount) {
            state.balances.put(uuid, Money.normalize(amount));
        }

        @Override
        public Map<UUID, Double> getAllBalances() {
            state.events.add(state.type + ":read-main");
            return Map.copyOf(state.balances);
        }

        @Override
        public void saveAll(Map<UUID, Double> balances) {
            balances.forEach(this::setBalance);
        }

        @Override
        public Map<UUID, PlayerIdentity> getAllPlayerIdentities() {
            state.events.add(state.type + ":read-identities");
            return Map.copyOf(state.playerIdentities);
        }

        @Override
        public void upsertPlayerIdentity(PlayerIdentity identity) {
            state.playerIdentities.merge(identity.playerId(), identity, PlayerIdentity::merge);
        }

        @Override
        public void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) {
            state.playerIdentities.clear();
            state.playerIdentities.putAll(identities);
        }

        @Override
        public Double getBankBalance(UUID uuid) {
            return state.bankBalances.get(uuid);
        }

        @Override
        public void setBankBalance(UUID uuid, double amount) {
            state.bankBalances.put(uuid, Money.normalize(amount));
        }

        @Override
        public Map<UUID, Double> getAllBankBalances() {
            state.events.add(state.type + ":read-bank");
            return Map.copyOf(state.bankBalances);
        }

        @Override
        public void saveAllBank(Map<UUID, Double> bankBalances) {
            bankBalances.forEach(this::setBankBalance);
        }

        @Override
        public void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
            state.events.add(state.type + ":replace-snapshot");
            state.snapshotReplacementCalls++;
            if (state.failFirstSnapshotReplacementAfterMutation && !state.snapshotReplacementFailed) {
                state.snapshotReplacementFailed = true;
                state.balances.clear();
                state.bankBalances.clear();
                snapshot.balances().entrySet().stream().findFirst()
                        .ifPresent(entry -> state.balances.put(entry.getKey(), entry.getValue()));
                throw new IOException("Simulated interrupted target import");
            }
            state.balances.clear();
            state.bankBalances.clear();
            state.balances.putAll(snapshot.balances());
            state.bankBalances.putAll(snapshot.bankBalances());
        }

        @Override
        public boolean transferMain(UUID source, UUID target, double amount) {
            return false;
        }

        @Override
        public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) {
            return false;
        }

        @Override
        public MaintenanceLease acquireMaintenanceLease(String reason) {
            if (!state.maintenanceCapable) return () -> { };
            if (state.maintenanceActive) throw new IllegalStateException("Maintenance already active");
            state.maintenanceActive = true;
            state.events.add(state.type + ":maintenance-acquire");
            return () -> {
                state.maintenanceActive = false;
                state.events.add(state.type + ":maintenance-release");
            };
        }

        @Override
        public void close() {
            state.events.add(state.type + ":close");
        }

        @Override
        public boolean isDebug() {
            return false;
        }
    }
}
