package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionMetadataCodec;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionOriginType;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MySQL/MariaDB storage backed by MariaDB Connector/J, with serialized access and explicit JDBC
 * transactions for every multi-account mutation. The public storage name remains MYSQL for config
 * compatibility. Schema migration is deliberately idempotent so an interrupted startup can be
 * retried safely without modifying existing balances.
 */
@SuppressWarnings("removal")
public class MySQLStorage implements Storage, WalletTransactionStore, StorageMaintenanceCoordinator {
    private static final int CURRENT_SCHEMA_VERSION = 6;
    private static final String SCHEMA_NAME = "sodaeconomy";
    private static final String SCHEMA_VERSION_TABLE = "sodaeconomy_schema_version";
    private static final String SCHEMA_LOCK_PREFIX = "sodaeconomy-schema-";
    private static final String TRANSACTIONS_TABLE = "wallet_transactions";
    private static final String SCHEDULED_JOBS_TABLE = "sodaeconomy_scheduled_jobs";
    private static final String BANK_INTEREST_JOB = "bank-interest";
    private static final String RUNTIME_STATE_TABLE = "sodaeconomy_runtime_state";
    private static final String PLAYER_IDENTITIES_TABLE = "player_identities";
    private static final String MUTATION_GATE = "wallet-mutations";
    // The legacy major-unit columns remain as compatibility mirrors during the v3 transition.
    // Runtime accounting deliberately uses only the exact minor-unit columns below.
    private static final String WALLET_BALANCE_COLUMN = "balance";
    private static final String BANK_BALANCE_COLUMN = "bank_balance";
    private static final String WALLET_BALANCE_MINOR_COLUMN = "balance_minor";
    private static final String BANK_BALANCE_MINOR_COLUMN = "bank_balance_minor";
    private static final int BALANCE_MIGRATION_BATCH_SIZE = 500;
    private static final int MYSQL_TRANSACTION_RETRY_ATTEMPTS = 4;
    private static final int MYSQL_DEADLOCK_ERROR = 1213;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT_ERROR = 1205;
    private static final String TRANSACTION_COLUMNS = "id, timestamp_epoch_millis, transaction_type, transaction_status, "
            + "origin_type, origin_player_id, origin_identifier, wallet_operation, source_player_id, target_player_id, "
            + "requested_amount_minor, applied_amount_minor, source_balance_before_minor, source_balance_after_minor, "
            + "target_balance_before_minor, target_balance_after_minor, reason, metadata, reversal_of_transaction_id, "
            + "batch_id, idempotency_key, failure_reason, failure_detail";

    private JavaPlugin plugin;
    private Connection connection;
    private boolean debug;
    private String url;
    private String user;
    private String password;
    private String schemaLockName;
    private String maintenanceLockName;
    private boolean transactionActive;
    private boolean jdbcMetadataLogged;
    private String localMaintenanceToken;

    @Override
    public synchronized void init(JavaPlugin plugin) throws Exception {
        close();
        this.plugin = plugin;
        debug = plugin.getConfig().getBoolean("debug.storage", false);

        String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
        int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
        String database = plugin.getConfig().getString("storage.mysql.database", "sodaeconomy");
        user = plugin.getConfig().getString("storage.mysql.user", "root");
        password = plugin.getConfig().getString("storage.mysql.password", "");
        MariaDbJdbcConfiguration.Result jdbcConfiguration = MariaDbJdbcConfiguration.create(
                host, port, database, readConnectionParameters(
                        plugin.getConfig().getConfigurationSection("storage.mysql.params")));
        url = jdbcConfiguration.jdbcUrl();
        jdbcConfiguration.warnings().forEach(warning -> plugin.getLogger().warning("[Storage] " + warning));
        schemaLockName = SCHEMA_LOCK_PREFIX + database;
        maintenanceLockName = "sodaeconomy-maintenance-" + database;
        jdbcMetadataLogged = false;

        loadMariaDbDriver();
        if (debug) {
            plugin.getLogger().info("[Storage] MariaDB Connector/J requested through Paper libraries for the "
                    + "configured MYSQL backend (jdbc:mariadb scheme).");
        }
        ensureConnected();
    }

    private Map<String, Object> readConnectionParameters(ConfigurationSection parameters) {
        Map<String, Object> values = new HashMap<>();
        if (parameters == null) return values;
        for (String key : parameters.getKeys(false)) {
            Object value = parameters.get(key);
            if (value != null) values.put(key, value);
        }
        return values;
    }

    private void loadMariaDbDriver() throws SQLException {
        try {
            Class.forName(MariaDbJdbcConfiguration.DRIVER_CLASS);
        } catch (ClassNotFoundException exception) {
            throw new JdbcDriverUnavailableException("MariaDB Connector/J is unavailable. Paper must resolve "
                    + MariaDbJdbcConfiguration.CONNECTOR_COORDINATES
                    + " from the plugin.yml libraries section before MYSQL storage can start.", exception);
        }
    }

    private void ensureConnected() throws Exception {
        if (connection != null) {
            try {
                if (!connection.isClosed() && connection.isValid(2)) return;
            } catch (SQLException ignored) {
                // Recreate a broken connection below.
            }
            if (transactionActive) {
                throw new SQLException("MySQL connection was lost during an active transaction");
            }
            close();
        }
        if (transactionActive) {
            throw new SQLException("MySQL connection was lost during an active transaction");
        }

        connection = DriverManager.getConnection(url, user, password);
        logJdbcMetadataOnce();
        try {
            ensureSchema();
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    private void logJdbcMetadataOnce() {
        if (!debug || jdbcMetadataLogged || connection == null) return;
        try {
            var metadata = connection.getMetaData();
            plugin.getLogger().info("[Storage] JDBC driver: " + metadata.getDriverName() + " "
                    + metadata.getDriverVersion() + "; database: " + metadata.getDatabaseProductName() + " "
                    + metadata.getDatabaseProductVersion() + ".");
            jdbcMetadataLogged = true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("[Storage] JDBC connection succeeded, but driver metadata could not be read: "
                    + exception.getMessage());
        }
    }

    private void ensureSchema() throws Exception {
        if (!acquireSchemaLock()) {
            throw new SQLException("Timed out while waiting for the SodaEconomy schema migration lock");
        }

        try {
            ensureSchemaVersionTable();
            int version = readSchemaVersion();
            if (version > CURRENT_SCHEMA_VERSION) {
                throw new SQLException("Database schema version " + version + " is newer than supported version "
                        + CURRENT_SCHEMA_VERSION);
            }

            while (version < CURRENT_SCHEMA_VERSION) {
                if (version == 0) {
                    migrateToVersionOne();
                    version = 1;
                    continue;
                }
                if (version == 1) {
                    migrateToVersionTwo();
                    version = 2;
                    continue;
                }
                if (version == 2) {
                    migrateToVersionThree();
                    version = 3;
                    continue;
                }
                if (version == 3) {
                    migrateToVersionFour();
                    version = 4;
                    continue;
                }
                if (version == 4) {
                    migrateToVersionFive();
                    version = 5;
                    continue;
                }
                if (version == 5) {
                    migrateToVersionSix();
                    version = 6;
                    continue;
                }
                throw new SQLException("No migration is available from database schema version " + version);
            }
            verifyExactBalanceSchema();
            ensureWalletTransactionsTable();
            ensureWalletTransactionIndexes();
            ensureScheduledJobsTable();
            ensureRuntimeStateTable();
            ensurePlayerIdentitiesTable();
            // The named schema lock also serializes the one-time baseline journal import across
            // multiple servers sharing this database. Without it, concurrent startups could each
            // observe an empty journal and create duplicate legacy opening entries.
            bootstrapLegacyOpeningBalances();
        } finally {
            releaseSchemaLock();
        }
    }

    private boolean acquireSchemaLock() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, schemaLockName);
            statement.setInt(2, 10);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private void releaseSchemaLock() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, schemaLockName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("MySQL did not return a schema-lock release result");
                }
            }
        }
    }

    private void ensureSchemaVersionTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + SCHEMA_VERSION_TABLE
                    + " (schema_name VARCHAR(64) PRIMARY KEY, version INT NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO " + SCHEMA_VERSION_TABLE
                + "(schema_name, version) VALUES(?, 0)")) {
            statement.setString(1, SCHEMA_NAME);
            statement.executeUpdate();
        }
    }

    private int readSchemaVersion() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT version FROM " + SCHEMA_VERSION_TABLE
                + " WHERE schema_name=?")) {
            statement.setString(1, SCHEMA_NAME);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Could not read the SodaEconomy schema version");
                return rows.getInt(1);
            }
        }
    }

    private void migrateToVersionOne() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances (uuid VARCHAR(36) PRIMARY KEY, "
                    + "balance DOUBLE NOT NULL, bank_balance DOUBLE NOT NULL DEFAULT 0)");
        }
        if (!hasColumn("balances", "bank_balance")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE balances ADD COLUMN bank_balance DOUBLE NOT NULL DEFAULT 0");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + SCHEMA_VERSION_TABLE
                + " SET version=? WHERE schema_name=?")) {
            statement.setInt(1, 1);
            statement.setString(2, SCHEMA_NAME);
            if (statement.executeUpdate() != 1) throw new SQLException("Could not update the SodaEconomy schema version");
        }
    }

    private void migrateToVersionTwo() throws SQLException {
        ensureWalletTransactionsTable();
        ensureWalletTransactionIndexes();
        updateSchemaVersion(2);
    }

    /**
     * Promotes legacy floating-point account balances to exact minor units. MySQL implicitly
     * commits DDL, so each phase is intentionally idempotent. A failed or interrupted migration
     * leaves the schema version at two and is safely completed during the next startup.
     */
    private void migrateToVersionThree() throws Exception {
        ensureMinorBalanceColumns();
        migrateLegacyBalancesToMinorUnits();
        ensureMinorBalanceColumnsAreNotNull();
        updateSchemaVersion(3);
    }

    /** Adds durable coordination state for scheduled jobs shared by multiple Paper instances. */
    private void migrateToVersionFour() throws SQLException {
        ensureScheduledJobsTable();
        updateSchemaVersion(4);
    }

    /** Adds a shared maintenance gate used by cross-instance storage migrations. */
    private void migrateToVersionFive() throws SQLException {
        ensureRuntimeStateTable();
        updateSchemaVersion(5);
    }

    /** Adds the cross-server player identity registry. */
    private void migrateToVersionSix() throws SQLException {
        ensurePlayerIdentitiesTable();
        updateSchemaVersion(6);
    }

    private void ensureMinorBalanceColumns() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn("balances", WALLET_BALANCE_MINOR_COLUMN)) {
                statement.executeUpdate("ALTER TABLE balances ADD COLUMN " + WALLET_BALANCE_MINOR_COLUMN
                        + " BIGINT NULL");
            }
            if (!hasColumn("balances", BANK_BALANCE_MINOR_COLUMN)) {
                statement.executeUpdate("ALTER TABLE balances ADD COLUMN " + BANK_BALANCE_MINOR_COLUMN
                        + " BIGINT NULL");
            }
        }
    }

    private void migrateLegacyBalancesToMinorUnits() throws Exception {
        inTransaction(() -> {
            String lastStorageKey = "";
            while (true) {
                List<BalanceMigration> batch = readBalanceMigrationBatch(lastStorageKey);
                if (batch.isEmpty()) {
                    return null;
                }
                writeBalanceMigrationBatch(batch);
                lastStorageKey = batch.get(batch.size() - 1).storageKey();
            }
        }, false);
    }

    private List<BalanceMigration> readBalanceMigrationBatch(String lastStorageKey) throws SQLException {
        String sql = "SELECT uuid," + WALLET_BALANCE_COLUMN + "," + BANK_BALANCE_COLUMN + ","
                + WALLET_BALANCE_MINOR_COLUMN + "," + BANK_BALANCE_MINOR_COLUMN + " FROM balances WHERE uuid>? "
                + "ORDER BY uuid ASC LIMIT ? FOR UPDATE";
        List<BalanceMigration> batch = new ArrayList<>(BALANCE_MIGRATION_BATCH_SIZE);
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, lastStorageKey);
            select.setInt(2, BALANCE_MIGRATION_BATCH_SIZE);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String storageKey = rows.getString("uuid");
                    UUID playerId = readRequiredUuid(rows, "uuid", "balances");
                    Long walletMinor = readNullableLong(rows, WALLET_BALANCE_MINOR_COLUMN);
                    Long bankMinor = readNullableLong(rows, BANK_BALANCE_MINOR_COLUMN);
                    if (walletMinor == null || bankMinor == null) {
                        walletMinor = legacyBalanceToMinor(rows, WALLET_BALANCE_COLUMN, playerId);
                        bankMinor = legacyBalanceToMinor(rows, BANK_BALANCE_COLUMN, playerId);
                    } else {
                        validateNonNegativeMinor(walletMinor, WALLET_BALANCE_MINOR_COLUMN, playerId);
                        validateNonNegativeMinor(bankMinor, BANK_BALANCE_MINOR_COLUMN, playerId);
                    }
                    batch.add(new BalanceMigration(storageKey, walletMinor, bankMinor));
                }
            }
        }
        return batch;
    }

    private void writeBalanceMigrationBatch(List<BalanceMigration> batch) throws SQLException {
        String sql = "UPDATE balances SET " + WALLET_BALANCE_COLUMN + "=?, " + BANK_BALANCE_COLUMN + "=?, "
                + WALLET_BALANCE_MINOR_COLUMN + "=?, " + BANK_BALANCE_MINOR_COLUMN + "=? WHERE uuid=?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            for (BalanceMigration balance : batch) {
                update.setDouble(1, Money.fromMinorUnits(balance.walletMinor()));
                update.setDouble(2, Money.fromMinorUnits(balance.bankMinor()));
                update.setLong(3, balance.walletMinor());
                update.setLong(4, balance.bankMinor());
                update.setString(5, balance.storageKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private long legacyBalanceToMinor(ResultSet rows, String column, UUID playerId) throws SQLException {
        double value = rows.getDouble(column);
        if (rows.wasNull() || !Money.isValid(value) || value < 0D) {
            throw new SQLException("The legacy " + column + " balance is invalid for " + playerId);
        }
        try {
            return Money.toMinorUnits(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("The legacy " + column + " balance is out of range for " + playerId, exception);
        }
    }

    private void ensureMinorBalanceColumnsAreNotNull() throws SQLException {
        ensureNoNullMinorBalanceColumns();
        try (Statement statement = connection.createStatement()) {
            if (isColumnNullable("balances", WALLET_BALANCE_MINOR_COLUMN)) {
                statement.executeUpdate("ALTER TABLE balances MODIFY COLUMN " + WALLET_BALANCE_MINOR_COLUMN
                        + " BIGINT NOT NULL");
            }
            if (isColumnNullable("balances", BANK_BALANCE_MINOR_COLUMN)) {
                statement.executeUpdate("ALTER TABLE balances MODIFY COLUMN " + BANK_BALANCE_MINOR_COLUMN
                        + " BIGINT NOT NULL DEFAULT 0");
            }
        }
    }

    private void verifyExactBalanceSchema() throws SQLException {
        if (!hasColumn("balances", WALLET_BALANCE_MINOR_COLUMN)
                || !hasColumn("balances", BANK_BALANCE_MINOR_COLUMN)) {
            throw new SQLException("Schema version " + CURRENT_SCHEMA_VERSION
                    + " is missing required exact account balance columns");
        }
        if (isColumnNullable("balances", WALLET_BALANCE_MINOR_COLUMN)
                || isColumnNullable("balances", BANK_BALANCE_MINOR_COLUMN)) {
            throw new SQLException("Schema version " + CURRENT_SCHEMA_VERSION
                    + " contains nullable exact account balance columns");
        }
    }

    private void ensureNoNullMinorBalanceColumns() throws SQLException {
        String sql = "SELECT COUNT(*) FROM balances WHERE " + WALLET_BALANCE_MINOR_COLUMN + " IS NULL OR "
                + BANK_BALANCE_MINOR_COLUMN + " IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || rows.getLong(1) != 0L) {
                throw new SQLException("Could not populate every exact account balance during schema migration");
            }
        }
    }

    private boolean isColumnNullable(String table, String column) throws SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name=? AND column_name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Required column " + table + "." + column + " does not exist");
                }
                return "YES".equalsIgnoreCase(rows.getString(1));
            }
        }
    }

    private void updateSchemaVersion(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + SCHEMA_VERSION_TABLE
                + " SET version=? WHERE schema_name=?")) {
            statement.setInt(1, version);
            statement.setString(2, SCHEMA_NAME);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not update the SodaEconomy schema version");
            }
        }
    }

    private void ensureScheduledJobsTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + SCHEDULED_JOBS_TABLE + " ("
                    + "job_name VARCHAR(64) PRIMARY KEY, "
                    + "last_run_epoch_millis BIGINT NOT NULL, "
                    + "interval_millis BIGINT NOT NULL, "
                    + "configuration_signature VARCHAR(160) NOT NULL"
                    + ")");
        }
    }

    private void ensureRuntimeStateTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + RUNTIME_STATE_TABLE
                    + " (state_name VARCHAR(64) PRIMARY KEY, maintenance_active BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "owner_token VARCHAR(64) NULL, reason VARCHAR(255) NULL, updated_epoch_millis BIGINT NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT IGNORE INTO " + RUNTIME_STATE_TABLE
                + "(state_name, maintenance_active, owner_token, reason, updated_epoch_millis) VALUES(?, FALSE, NULL, NULL, 0)")) {
            statement.setString(1, MUTATION_GATE);
            statement.executeUpdate();
        }
    }

    private void ensurePlayerIdentitiesTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PLAYER_IDENTITIES_TABLE + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, last_known_name VARCHAR(128) NOT NULL, "
                    + "normalized_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, "
                    + "last_login_epoch_millis BIGINT NOT NULL, "
                    + "player_type VARCHAR(16) NOT NULL, INDEX idx_player_identities_normalized_name(normalized_name)"
                    + ")");
        }
    }

    @Override
    public synchronized MaintenanceLease acquireMaintenanceLease(String reason) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.acquireMaintenanceLease", MySQLStorage.class);
        if (localMaintenanceToken != null) {
            throw new IllegalStateException("This MySQL storage instance already owns a maintenance lease");
        }
        ensureConnected();
        if (!acquireNamedLock(maintenanceLockName, 30)) {
            throw new SQLException("Timed out while waiting for another SodaEconomy maintenance operation");
        }
        String token = UUID.randomUUID().toString();
        try {
            inTransaction(() -> {
            try (PreparedStatement select = connection.prepareStatement("SELECT maintenance_active, owner_token FROM "
                    + RUNTIME_STATE_TABLE + " WHERE state_name=? FOR UPDATE")) {
                select.setString(1, MUTATION_GATE);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) throw new SQLException("The shared mutation gate is missing");
                    if (rows.getBoolean("maintenance_active")) {
                        throw new SQLException("Another SodaEconomy instance is already performing database maintenance");
                    }
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE " + RUNTIME_STATE_TABLE
                    + " SET maintenance_active=TRUE, owner_token=?, reason=?, updated_epoch_millis=? WHERE state_name=?")) {
                update.setString(1, token);
                update.setString(2, reason == null ? "storage migration" : reason);
                update.setLong(3, System.currentTimeMillis());
                update.setString(4, MUTATION_GATE);
                if (update.executeUpdate() != 1) throw new SQLException("Could not activate the shared mutation gate");
            }
                return null;
            }, false);
            localMaintenanceToken = token;
            plugin.getLogger().warning("[Storage] MySQL maintenance gate activated: "
                    + (reason == null ? "storage migration" : reason));
            return () -> releaseMaintenanceLease(token);
        } catch (Exception exception) {
            try {
                releaseNamedLock(maintenanceLockName);
            } catch (SQLException releaseFailure) {
                exception.addSuppressed(releaseFailure);
            }
            throw exception;
        }
    }

    private synchronized void releaseMaintenanceLease(String token) throws Exception {
        if (!Objects.equals(localMaintenanceToken, token)) return;
        Exception failure = null;
        try {
            inTransaction(() -> {
                try (PreparedStatement update = connection.prepareStatement("UPDATE " + RUNTIME_STATE_TABLE
                        + " SET maintenance_active=FALSE, owner_token=NULL, reason=NULL, updated_epoch_millis=? "
                        + "WHERE state_name=? AND owner_token=?")) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setString(2, MUTATION_GATE);
                    update.setString(3, token);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("The shared mutation gate is no longer owned by this server");
                    }
                }
                return null;
            }, false);
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure == null) {
                releaseNamedLock(maintenanceLockName);
                localMaintenanceToken = null;
                plugin.getLogger().info("[Storage] MySQL maintenance gate released.");
            }
        }
    }

    private boolean acquireNamedLock(String name, int timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            statement.setString(1, name);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private void releaseNamedLock(String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("MySQL did not return a named-lock release result");
                int result = rows.getInt(1);
                if (rows.wasNull() || result != 1) {
                    throw new SQLException("This connection no longer owns the MySQL named lock " + name);
                }
            }
        }
    }

    private void assertMutationsAllowed() throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT maintenance_active, owner_token FROM "
                + RUNTIME_STATE_TABLE + " WHERE state_name=? FOR SHARE")) {
            select.setString(1, MUTATION_GATE);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) throw new SQLException("The shared mutation gate is missing");
                boolean active = rows.getBoolean("maintenance_active");
                String owner = rows.getString("owner_token");
                if (active && !Objects.equals(owner, localMaintenanceToken)) {
                    if (acquireNamedLock(maintenanceLockName, 0)) {
                        try (PreparedStatement recover = connection.prepareStatement("UPDATE " + RUNTIME_STATE_TABLE
                                + " SET maintenance_active=FALSE, owner_token=NULL, reason=NULL, updated_epoch_millis=? "
                                + "WHERE state_name=? AND maintenance_active=TRUE")) {
                            recover.setLong(1, System.currentTimeMillis());
                            recover.setString(2, MUTATION_GATE);
                            recover.executeUpdate();
                        } finally {
                            releaseNamedLock(maintenanceLockName);
                        }
                        plugin.getLogger().warning("[Storage] Recovered an abandoned MySQL maintenance gate after the "
                                + "previous owner connection disappeared.");
                        return;
                    }
                    throw new SQLException("SodaEconomy database maintenance is active; wallet mutations are temporarily unavailable");
                }
            }
        }
    }


    private void ensureWalletTransactionsTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TRANSACTIONS_TABLE + " ("
                    + "id VARCHAR(36) PRIMARY KEY, "
                    + "timestamp_epoch_millis BIGINT NOT NULL, "
                    + "transaction_type VARCHAR(64) NOT NULL, "
                    + "transaction_status VARCHAR(16) NOT NULL, "
                    + "origin_type VARCHAR(16) NOT NULL, "
                    + "origin_player_id VARCHAR(36) NULL, "
                    + "origin_identifier VARCHAR(128) NOT NULL, "
                    + "wallet_operation VARCHAR(16) NOT NULL, "
                    + "source_player_id VARCHAR(36) NULL, "
                    + "target_player_id VARCHAR(36) NULL, "
                    + "requested_amount_minor BIGINT NOT NULL, "
                    + "applied_amount_minor BIGINT NOT NULL, "
                    + "source_balance_before_minor BIGINT NULL, "
                    + "source_balance_after_minor BIGINT NULL, "
                    + "target_balance_before_minor BIGINT NULL, "
                    + "target_balance_after_minor BIGINT NULL, "
                    + "reason VARCHAR(512) NOT NULL, "
                    + "metadata TEXT NOT NULL, "
                    + "reversal_of_transaction_id VARCHAR(36) NULL, "
                    + "successful_reversal_of_id VARCHAR(36) NULL, "
                    + "batch_id VARCHAR(36) NULL, "
                    + "idempotency_key VARCHAR(128) NULL, "
                    + "failure_reason VARCHAR(64) NOT NULL, "
                    + "failure_detail VARCHAR(512) NULL, "
                    + "UNIQUE KEY uq_wallet_transactions_successful_reversal (successful_reversal_of_id), "
                    + "UNIQUE KEY uq_wallet_transactions_idempotency (idempotency_key)"
                    + ")");
        }
    }

    private void ensureWalletTransactionIndexes() throws SQLException {
        ensureIndex("idx_wallet_transactions_source_timestamp", "source_player_id, timestamp_epoch_millis, id");
        ensureIndex("idx_wallet_transactions_target_timestamp", "target_player_id, timestamp_epoch_millis, id");
        ensureIndex("idx_wallet_transactions_timestamp", "timestamp_epoch_millis, id");
        ensureIndex("idx_wallet_transactions_type_status_timestamp", "transaction_type, transaction_status, timestamp_epoch_millis, id");
    }

    private void ensureIndex(String indexName, String columns) throws SQLException {
        String existsSql = "SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() "
                + "AND table_name=? AND index_name=?";
        try (PreparedStatement statement = connection.prepareStatement(existsSql)) {
            statement.setString(1, TRANSACTIONS_TABLE);
            statement.setString(2, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + TRANSACTIONS_TABLE + "(" + columns + ")");
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = ? AND column_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Override public synchronized Double getBalance(UUID uuid) throws Exception {
        return getValue(WALLET_BALANCE_MINOR_COLUMN, uuid);
    }

    @Override public synchronized Long getBalanceMinorUnits(UUID uuid) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.getBalanceMinorUnits", MySQLStorage.class);
        return getValueMinor(WALLET_BALANCE_MINOR_COLUMN, uuid);
    }

    @Override
    public synchronized Map<UUID, PlayerIdentity> getAllPlayerIdentities() throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.getAllPlayerIdentities", MySQLStorage.class);
        ensureConnected();
        Map<UUID, PlayerIdentity> identities = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, last_known_name, "
                + "normalized_name, last_login_epoch_millis, player_type FROM " + PLAYER_IDENTITIES_TABLE);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                PlayerIdentity identity = readPlayerIdentity(rows);
                identities.put(identity.playerId(), identity);
            }
        }
        return Map.copyOf(identities);
    }

    @Override
    public synchronized Optional<PlayerIdentity> findPlayerIdentity(UUID playerId) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.findPlayerIdentity", MySQLStorage.class);
        Objects.requireNonNull(playerId, "playerId");
        ensureConnected();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, last_known_name, "
                + "normalized_name, last_login_epoch_millis, player_type FROM " + PLAYER_IDENTITIES_TABLE
                + " WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readPlayerIdentity(rows)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized List<PlayerIdentity> findPlayerIdentitiesByNormalizedName(String normalizedName)
            throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.findPlayerIdentitiesByNormalizedName", MySQLStorage.class);
        Objects.requireNonNull(normalizedName, "normalizedName");
        ensureConnected();
        List<PlayerIdentity> identities = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, last_known_name, "
                + "normalized_name, last_login_epoch_millis, player_type FROM " + PLAYER_IDENTITIES_TABLE
                + " WHERE normalized_name=? ORDER BY uuid ASC")) {
            statement.setString(1, normalizedName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) identities.add(readPlayerIdentity(rows));
            }
        }
        return List.copyOf(identities);
    }

    @Override
    public synchronized Map<UUID, PlayerIdentity> findPlayerIdentities(Set<UUID> playerIds) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.findPlayerIdentities", MySQLStorage.class);
        Objects.requireNonNull(playerIds, "playerIds");
        if (playerIds.isEmpty()) return Map.of();
        ensureConnected();
        Map<UUID, PlayerIdentity> identities = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(playerIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, last_known_name, "
                + "normalized_name, last_login_epoch_millis, player_type FROM " + PLAYER_IDENTITIES_TABLE
                + " WHERE uuid IN (" + placeholders + ")")) {
            int index = 1;
            for (UUID playerId : playerIds) statement.setString(index++, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    PlayerIdentity identity = readPlayerIdentity(rows);
                    identities.put(identity.playerId(), identity);
                }
            }
        }
        return Map.copyOf(identities);
    }

    @Override
    public synchronized void upsertPlayerIdentity(PlayerIdentity identity) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.upsertPlayerIdentity", MySQLStorage.class);
        Objects.requireNonNull(identity, "identity");
        inTransaction(() -> {
            upsertPlayerIdentityInternal(identity);
            return null;
        });
    }

    @Override
    public synchronized void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.replacePlayerIdentities", MySQLStorage.class);
        Objects.requireNonNull(identities, "identities");
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + PLAYER_IDENTITIES_TABLE);
            }
            for (PlayerIdentity identity : identities.values()) upsertPlayerIdentityInternal(identity);
            return null;
        });
    }

    @Override public synchronized Double getBankBalance(UUID uuid) throws Exception {
        return getValue(BANK_BALANCE_MINOR_COLUMN, uuid);
    }

    @Override
    public synchronized double getOrCreateBalance(UUID uuid, double initial) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.getOrCreateBalance", MySQLStorage.class);
        return Money.fromMinorUnits(ensureWalletAccount(uuid, Money.toMinorUnits(initial), java.time.Instant.now()).balanceMinor());
    }

    @Override public synchronized void setBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.setBalance", MySQLStorage.class);
        setValue(WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, uuid, amount);
    }

    @Override public synchronized void setBankBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.setBankBalance", MySQLStorage.class);
        setValue(BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, uuid, amount);
    }

    private Double getValue(String minorColumn, UUID uuid) throws Exception {
        Long value = getValueMinor(minorColumn, uuid);
        return value == null ? null : Money.fromMinorUnits(value);
    }

    private Long getValueMinor(String minorColumn, UUID uuid) throws Exception {
        Objects.requireNonNull(uuid, "uuid");
        ensureConnected();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + minorColumn + " FROM balances WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long value = rows.getLong(1);
                if (rows.wasNull() || value < 0L) {
                    throw new SQLException("The stored " + minorColumn + " balance is invalid for " + uuid);
                }
                return value;
            }
        }
    }

    private void setValue(String minorColumn, String legacyColumn, UUID uuid, double amount) throws Exception {
        Objects.requireNonNull(uuid, "uuid");
        long amountMinor = Money.toMinorUnits(amount);
        setValueMinor(minorColumn, legacyColumn, uuid, amountMinor);
    }

    private void setValueMinor(String minorColumn, String legacyColumn, UUID uuid, long amountMinor) throws Exception {
        validateNonNegativeMinor(amountMinor, minorColumn, uuid);
        ensureConnected();
        double legacyValue = Money.fromMinorUnits(amountMinor);
        String sql = "INSERT INTO balances(uuid," + WALLET_BALANCE_COLUMN + "," + BANK_BALANCE_COLUMN + ","
                + WALLET_BALANCE_MINOR_COLUMN + "," + BANK_BALANCE_MINOR_COLUMN + ") VALUES(?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE " + minorColumn + "=?, " + legacyColumn + "=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setDouble(2, WALLET_BALANCE_MINOR_COLUMN.equals(minorColumn) ? legacyValue : 0D);
            statement.setDouble(3, BANK_BALANCE_MINOR_COLUMN.equals(minorColumn) ? legacyValue : 0D);
            statement.setLong(4, WALLET_BALANCE_MINOR_COLUMN.equals(minorColumn) ? amountMinor : 0L);
            statement.setLong(5, BANK_BALANCE_MINOR_COLUMN.equals(minorColumn) ? amountMinor : 0L);
            statement.setLong(6, amountMinor);
            statement.setDouble(7, legacyValue);
            statement.executeUpdate();
        }
    }

    @Override public synchronized Map<UUID, Double> getAllBalances() throws Exception {
        return getAll(WALLET_BALANCE_MINOR_COLUMN);
    }

    @Override public synchronized Map<UUID, Double> getAllBankBalances() throws Exception {
        return getAll(BANK_BALANCE_MINOR_COLUMN);
    }

    @Override public synchronized Map<UUID, Long> getAllBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.getAllBalanceMinorUnits", MySQLStorage.class);
        return getAllMinorUnits(WALLET_BALANCE_MINOR_COLUMN);
    }

    @Override public synchronized Map<UUID, Long> getAllBankBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.getAllBankBalanceMinorUnits", MySQLStorage.class);
        return getAllMinorUnits(BANK_BALANCE_MINOR_COLUMN);
    }

    private Map<UUID, Double> getAll(String minorColumn) throws Exception {
        ensureConnected();
        Map<UUID, Double> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid," + minorColumn + " FROM balances");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID playerId = UUID.fromString(rows.getString(1));
                    long value = rows.getLong(2);
                    if (!rows.wasNull() && value >= 0L) {
                        result.put(playerId, Money.fromMinorUnits(value));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore malformed legacy rows instead of making the complete storage unreadable.
                }
            }
        }
        return result;
    }

    private Map<UUID, Long> getAllMinorUnits(String minorColumn) throws Exception {
        ensureConnected();
        Map<UUID, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid," + minorColumn + " FROM balances");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId = readRequiredUuid(rows, "uuid", "balances");
                long amountMinor = rows.getLong(minorColumn);
                if (rows.wasNull() || amountMinor < 0L) {
                    throw new SQLException("Balances contains an invalid " + minorColumn + " value for " + playerId);
                }
                if (result.put(playerId, amountMinor) != null) {
                    throw new SQLException("Balances contains duplicate UUID values after normalization: " + playerId);
                }
            }
        }
        return Map.copyOf(result);
    }

    @Override public synchronized void saveAll(Map<UUID, Double> values) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.saveAll", MySQLStorage.class);
        saveAll(WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, values);
    }

    @Override public synchronized void saveAllBank(Map<UUID, Double> values) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.saveAllBank", MySQLStorage.class);
        saveAll(BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, values);
    }

    /**
     * Persists bank-interest updates without converting the canonical amount through a
     * {@code double}. The whole batch shares one database transaction so a failed interest run
     * cannot leave only a subset of bank accounts updated.
     */
    @Override
    public synchronized void saveAllBankMinorUnits(Map<UUID, Long> bankBalances) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.saveAllBankMinorUnits", MySQLStorage.class);
        Objects.requireNonNull(bankBalances, "bankBalances");
        Map<UUID, Long> exactBalances = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : bankBalances.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), "bankBalances contains a null UUID");
            Long amountMinor = Objects.requireNonNull(entry.getValue(), "bankBalances contains a null balance");
            validateNonNegativeMinor(amountMinor, BANK_BALANCE_MINOR_COLUMN, playerId);
            exactBalances.put(playerId, amountMinor);
        }

        inTransaction(() -> {
            for (Map.Entry<UUID, Long> entry : exactBalances.entrySet()) {
                setValueMinor(BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, entry.getKey(), entry.getValue());
            }
            return null;
        });
    }

    @Override
    public synchronized BankInterestResult applyBankInterest(BigDecimal rate, long maximumInterestMinor,
                                                              Duration minimumInterval, Instant runAt) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.applyBankInterest", MySQLStorage.class);
        if (rate == null || rate.signum() <= 0 || maximumInterestMinor < 0L
                || minimumInterval == null || minimumInterval.isNegative() || runAt == null) {
            throw new IllegalArgumentException("The bank-interest parameters must be valid");
        }
        long intervalMillis;
        long runAtMillis;
        try {
            intervalMillis = minimumInterval.toMillis();
            runAtMillis = runAt.toEpochMilli();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The bank-interest interval or timestamp is out of range", exception);
        }
        String signature = bankInterestConfigurationSignature(rate, maximumInterestMinor, intervalMillis);
        return inTransaction(() -> {
            if (intervalMillis > 0L && !claimBankInterestInterval(runAtMillis, intervalMillis, signature)) {
                return BankInterestResult.skipped();
            }

            List<BankInterestUpdate> updates = lockAndCalculateBankInterest(rate, maximumInterestMinor);
            persistBankInterestUpdates(updates);
            if (intervalMillis > 0L) {
                completeBankInterestInterval(runAtMillis);
            }
            return BankInterestResult.executed(updates.size());
        });
    }

    private boolean claimBankInterestInterval(long runAtMillis, long intervalMillis, String signature)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT IGNORE INTO " + SCHEDULED_JOBS_TABLE
                + "(job_name, last_run_epoch_millis, interval_millis, configuration_signature) VALUES(?, 0, ?, ?)")) {
            insert.setString(1, BANK_INTEREST_JOB);
            insert.setLong(2, intervalMillis);
            insert.setString(3, signature);
            insert.executeUpdate();
        }

        String sql = "SELECT last_run_epoch_millis, interval_millis, configuration_signature FROM "
                + SCHEDULED_JOBS_TABLE + " WHERE job_name=? FOR UPDATE";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, BANK_INTEREST_JOB);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Could not claim the shared bank-interest schedule");
                }
                long lastRunMillis = rows.getLong("last_run_epoch_millis");
                long storedIntervalMillis = rows.getLong("interval_millis");
                String storedSignature = rows.getString("configuration_signature");
                if (storedIntervalMillis != intervalMillis || !signature.equals(storedSignature)) {
                    throw new SQLException("Bank-interest configuration differs between servers sharing this MySQL database");
                }
                if (lastRunMillis > runAtMillis) {
                    return false;
                }
                return lastRunMillis == 0L || runAtMillis - lastRunMillis >= intervalMillis;
            }
        }
    }

    private void completeBankInterestInterval(long runAtMillis) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + SCHEDULED_JOBS_TABLE
                + " SET last_run_epoch_millis=? WHERE job_name=?")) {
            update.setLong(1, runAtMillis);
            update.setString(2, BANK_INTEREST_JOB);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Could not complete the shared bank-interest schedule");
            }
        }
    }

    private List<BankInterestUpdate> lockAndCalculateBankInterest(BigDecimal rate, long maximumInterestMinor)
            throws Exception {
        List<BankInterestUpdate> updates = new ArrayList<>();
        String sql = "SELECT uuid," + BANK_BALANCE_MINOR_COLUMN + " FROM balances WHERE "
                + BANK_BALANCE_MINOR_COLUMN + ">0 ORDER BY uuid ASC FOR UPDATE";
        try (PreparedStatement select = connection.prepareStatement(sql);
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                UUID playerId = readRequiredUuid(rows, "uuid", "balances");
                long balanceMinor = rows.getLong(BANK_BALANCE_MINOR_COLUMN);
                if (rows.wasNull() || balanceMinor < 0L) {
                    throw new SQLException("Balances contains an invalid bank balance for " + playerId);
                }
                long interestMinor;
                try {
                    interestMinor = Money.toMinorUnits(Money.toBigDecimal(balanceMinor).multiply(rate));
                } catch (IllegalArgumentException exception) {
                    if (debug && plugin != null) {
                        plugin.getLogger().warning("[Banking] Skipped an out-of-range interest calculation for "
                                + playerId + ".");
                    }
                    continue;
                }
                if (maximumInterestMinor > 0L) {
                    interestMinor = Math.min(interestMinor, maximumInterestMinor);
                }
                if (interestMinor <= 0L) {
                    continue;
                }
                try {
                    updates.add(new BankInterestUpdate(playerId, balanceMinor,
                            Math.addExact(balanceMinor, interestMinor)));
                } catch (ArithmeticException exception) {
                    if (debug && plugin != null) {
                        plugin.getLogger().warning("[Banking] Skipped an out-of-range bank balance for "
                                + playerId + ".");
                    }
                }
            }
        }
        return updates;
    }

    private void persistBankInterestUpdates(List<BankInterestUpdate> updates) throws SQLException {
        if (updates.isEmpty()) {
            return;
        }
        String sql = "UPDATE balances SET " + BANK_BALANCE_MINOR_COLUMN + "=?, " + BANK_BALANCE_COLUMN
                + "=? WHERE uuid=? AND " + BANK_BALANCE_MINOR_COLUMN + "=?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            for (BankInterestUpdate interestUpdate : updates) {
                update.setLong(1, interestUpdate.afterMinor());
                update.setDouble(2, Money.fromMinorUnits(interestUpdate.afterMinor()));
                update.setString(3, interestUpdate.playerId().toString());
                update.setLong(4, interestUpdate.beforeMinor());
                update.addBatch();
            }
            int[] results = update.executeBatch();
            if (results.length != updates.size()) {
                throw new SQLException("MySQL did not return every bank-interest update result");
            }
            for (int result : results) {
                if (result != 1 && result != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("A bank account changed unexpectedly during the interest transaction");
                }
            }
        }
    }

    private static String bankInterestConfigurationSignature(BigDecimal rate, long maximumInterestMinor,
                                                              long intervalMillis) {
        return rate.stripTrailingZeros().toPlainString() + '|' + maximumInterestMinor + '|' + intervalMillis;
    }

    @Override
    public synchronized void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.replaceSnapshot", MySQLStorage.class);
        Objects.requireNonNull(snapshot, "snapshot");
        replaceMinorUnitSnapshot(toMinorUnits(snapshot.balances(), "wallet balances"),
                toMinorUnits(snapshot.bankBalances(), "bank balances"), snapshot.walletTransactions());
    }

    @Override
    public synchronized void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                                        List<TransactionRecord> walletTransactions) throws Exception {
        replaceMinorUnitSnapshot(balances, bankBalances, walletTransactions, getAllPlayerIdentities());
    }

    @Override
    public synchronized void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                                        List<TransactionRecord> walletTransactions,
                                                        Map<UUID, PlayerIdentity> playerIdentities) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.replaceMinorUnitSnapshot", MySQLStorage.class);
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(balances, bankBalances, walletTransactions,
                playerIdentities);
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + TRANSACTIONS_TABLE);
                statement.executeUpdate("DELETE FROM balances");
                statement.executeUpdate("DELETE FROM " + PLAYER_IDENTITIES_TABLE);
            }
            for (Map.Entry<UUID, Long> entry : snapshot.balances().entrySet()) {
                setValueMinor(WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<UUID, Long> entry : snapshot.bankBalances().entrySet()) {
                setValueMinor(BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, entry.getKey(), entry.getValue());
            }
            for (TransactionRecord record : snapshot.walletTransactions()) {
                insertTransaction(record);
            }
            for (PlayerIdentity identity : snapshot.playerIdentities().values()) {
                upsertPlayerIdentityInternal(identity);
            }
            if (snapshot.walletTransactions().isEmpty()) {
                bootstrapLegacyOpeningBalancesInTransaction();
            }
            return null;
        });
    }

    private static Map<UUID, Long> toMinorUnits(Map<UUID, Double> balances, String name) {
        Objects.requireNonNull(balances, name);
        Map<UUID, Long> converted = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            UUID playerId = Objects.requireNonNull(entry.getKey(), name + " contains a null UUID");
            Double amount = Objects.requireNonNull(entry.getValue(), name + " contains a null balance");
            converted.put(playerId, Money.toMinorUnits(amount));
        }
        return Map.copyOf(converted);
    }

    private void saveAll(String minorColumn, String legacyColumn, Map<UUID, Double> values) throws Exception {
        inTransaction(() -> {
            for (Map.Entry<UUID, Double> entry : values.entrySet()) {
                setValue(minorColumn, legacyColumn, entry.getKey(), entry.getValue());
            }
            return null;
        });
    }

    @Override public synchronized boolean transferMain(UUID source, UUID target, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.transferMain", MySQLStorage.class);
        return transfer(source, WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN,
                target, WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, amount);
    }

    @Override public synchronized boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.transferMainAndBank", MySQLStorage.class);
        return mainToBank
                ? transfer(uuid, WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN,
                uuid, BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, amount)
                : transfer(uuid, BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN,
                uuid, WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, amount);
    }

    private boolean transfer(UUID source, String sourceMinorColumn, String sourceLegacyColumn,
                             UUID target, String targetMinorColumn, String targetLegacyColumn,
                             double amount) throws Exception {
        if (!Money.isPositive(amount)) return false;
        if (sourceMinorColumn.equals(targetMinorColumn) && source.equals(target)) return false;
        long amountMinor = Money.toMinorUnits(amount);
        return inTransaction(() -> {
            Map<UUID, AccountBalances> balances = lockAccountBalances(source, target);
            AccountBalances sourceBalances = balances.get(source);
            AccountBalances targetBalances = balances.get(target);
            if (sourceBalances == null || targetBalances == null) {
                if (sourceBalances == null) return false;
                throw new SQLException("Target account missing");
            }
            long sourceBefore = sourceBalances.valueFor(sourceMinorColumn);
            if (sourceBefore < amountMinor) return false;
            long targetAfter;
            try {
                targetAfter = Math.addExact(targetBalances.valueFor(targetMinorColumn), amountMinor);
            } catch (ArithmeticException exception) {
                throw new SQLException("The target balance cannot be represented", exception);
            }
            updateAccountBalance(source, sourceMinorColumn, sourceLegacyColumn, sourceBefore - amountMinor);
            updateAccountBalance(target, targetMinorColumn, targetLegacyColumn, targetAfter);
            return true;
        });
    }

    @Override
    public synchronized WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor,
                                                                java.time.Instant timestamp) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.ensureWalletAccount", MySQLStorage.class);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (startingBalanceMinor < 0L) {
            throw new IllegalArgumentException("The starting balance must not be negative");
        }
        SQLException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MYSQL_TRANSACTION_RETRY_ATTEMPTS; attempt++) {
            try {
                return inTransaction(() -> ensureWalletAccountInTransaction(playerId, startingBalanceMinor, timestamp));
            } catch (SQLException exception) {
                if (!isTransientConcurrencyFailure(exception) || attempt == MYSQL_TRANSACTION_RETRY_ATTEMPTS) {
                    throw exception;
                }
                lastTransientFailure = exception;
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw lastTransientFailure == null
                ? new SQLException("MySQL wallet account retry loop ended without a result")
                : lastTransientFailure;
    }

    private WalletAccountState ensureWalletAccountInTransaction(UUID playerId, long startingBalanceMinor,
                                                                 java.time.Instant timestamp) throws Exception {
        boolean created;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT IGNORE INTO balances(uuid," + WALLET_BALANCE_COLUMN + "," + BANK_BALANCE_COLUMN + ","
                        + WALLET_BALANCE_MINOR_COLUMN + "," + BANK_BALANCE_MINOR_COLUMN + ") VALUES(?,?,0,?,0)")) {
            insert.setString(1, playerId.toString());
            insert.setDouble(2, Money.fromMinorUnits(startingBalanceMinor));
            insert.setLong(3, startingBalanceMinor);
            created = insert.executeUpdate() == 1;
        }
        Long balance = readWalletBalanceMinor(playerId, true);
        if (balance == null) {
            throw new SQLException("Could not create or read the wallet account");
        }
        if (created) {
            insertTransaction(TransactionRecords.initialBalance(playerId, startingBalanceMinor, timestamp));
        }
        return new WalletAccountState(balance, created);
    }

    @Override
    public synchronized TransactionRecord executeWalletTransaction(WalletTransactionRequest request,
                                                                    long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.executeWalletTransaction", MySQLStorage.class);
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0L) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        ensureConnected();
        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        return executeWalletTransactionWithTransientRetries(request, maximumBalanceMinor);
    }

    private TransactionRecord executeWalletTransactionWithTransientRetries(WalletTransactionRequest request,
                                                                          long maximumBalanceMinor) throws Exception {
        SQLException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MYSQL_TRANSACTION_RETRY_ATTEMPTS; attempt++) {
            try {
                return inTransaction(() -> executeWalletTransactionInTransaction(request, maximumBalanceMinor));
            } catch (SQLException exception) {
                Optional<TransactionRecord> concurrent = findExistingRequest(request);
                if (concurrent.isPresent()) {
                    return concurrent.get();
                }
                if (request.reversalOfTransactionId() != null
                        && hasSuccessfulReversal(request.reversalOfTransactionId())) {
                    return inTransaction(() -> persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                            "The referenced transaction has already been rolled back", null, null));
                }
                if (!isTransientConcurrencyFailure(exception) || attempt == MYSQL_TRANSACTION_RETRY_ATTEMPTS) {
                    throw exception;
                }
                lastTransientFailure = exception;
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw lastTransientFailure == null
                ? new SQLException("MySQL wallet transaction retry loop ended without a result")
                : lastTransientFailure;
    }

    @Override
    public synchronized TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request,
                                                                        boolean mainToBank,
                                                                        long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("MySQLStorage.executeWalletBankTransaction", MySQLStorage.class);
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0L) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        validateWalletBankRequest(request, mainToBank);
        ensureConnected();
        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        return executeWalletBankTransactionWithTransientRetries(request, mainToBank, maximumBalanceMinor);
    }

    private TransactionRecord executeWalletBankTransactionWithTransientRetries(WalletTransactionRequest request,
                                                                              boolean mainToBank,
                                                                              long maximumBalanceMinor) throws Exception {
        SQLException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MYSQL_TRANSACTION_RETRY_ATTEMPTS; attempt++) {
            try {
                return inTransaction(() -> executeWalletBankTransactionInTransaction(request, mainToBank,
                        maximumBalanceMinor));
            } catch (SQLException exception) {
                Optional<TransactionRecord> concurrent = findExistingRequest(request);
                if (concurrent.isPresent()) {
                    return concurrent.get();
                }
                if (request.reversalOfTransactionId() != null
                        && hasSuccessfulReversal(request.reversalOfTransactionId())) {
                    return inTransaction(() -> persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                            "The referenced transaction has already been rolled back", null, null));
                }
                if (!isTransientConcurrencyFailure(exception) || attempt == MYSQL_TRANSACTION_RETRY_ATTEMPTS) {
                    throw exception;
                }
                lastTransientFailure = exception;
                sleepBeforeRetry(attempt, exception);
            }
        }
        throw lastTransientFailure == null
                ? new SQLException("MySQL wallet-bank transaction retry loop ended without a result")
                : lastTransientFailure;
    }

    private TransactionRecord executeWalletTransactionInTransaction(WalletTransactionRequest request,
                                                                      long maximumBalanceMinor) throws Exception {
        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (request.reversalOfTransactionId() != null) {
            Optional<TransactionRecord> original = findTransactionInternal(request.reversalOfTransactionId());
            if (original.isEmpty() || !original.get().isSuccessful()) {
                return persistFailure(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The referenced transaction is not successful", null, null);
            }
            if (hasSuccessfulReversal(request.reversalOfTransactionId())) {
                return persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back", null, null);
            }
        }
        return switch (request.operation()) {
            case CREDIT -> executeCredit(request, maximumBalanceMinor);
            case DEBIT -> executeDebit(request);
            case TRANSFER -> executeTransfer(request, maximumBalanceMinor);
            case SET -> executeSet(request, maximumBalanceMinor);
        };
    }

    private TransactionRecord executeWalletBankTransactionInTransaction(WalletTransactionRequest request,
                                                                          boolean mainToBank,
                                                                          long maximumBalanceMinor) throws Exception {
        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (request.reversalOfTransactionId() != null) {
            Optional<TransactionRecord> original = findTransactionInternal(request.reversalOfTransactionId());
            if (original.isEmpty() || !original.get().isSuccessful()) {
                return persistFailure(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The referenced transaction is not successful", null, null);
            }
            if (hasSuccessfulReversal(request.reversalOfTransactionId())) {
                return persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back", null, null);
            }
        }

        UUID playerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
        Long walletBefore = readWalletBalanceMinor(playerId, true);
        Long bankBefore = readBankBalanceMinor(playerId, true);
        if (walletBefore == null || bankBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The wallet account does not exist for the bank transfer", mainToBank ? walletBefore : null,
                    mainToBank ? null : walletBefore);
        }
        return mainToBank
                ? executeWalletToBank(request, walletBefore, bankBefore)
                : executeBankToWallet(request, walletBefore, bankBefore, maximumBalanceMinor);
    }

    private TransactionRecord executeCredit(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception {
        Long targetBefore = readWalletBalanceMinor(request.targetPlayerId(), true);
        if (targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist", null, null);
        }
        long targetAfter;
        try {
            targetAfter = Math.addExact(targetBefore, request.amountMinor());
        } catch (ArithmeticException exception) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The target wallet balance cannot be represented", null, targetBefore);
        }
        if (exceedsMaximum(targetAfter, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum", null, targetBefore);
        }
        updateWalletBalance(request.targetPlayerId(), targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                null, null, targetBefore, targetAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeDebit(WalletTransactionRequest request) throws Exception {
        Long sourceBefore = readWalletBalanceMinor(request.sourcePlayerId(), true);
        if (sourceBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The source wallet account does not exist", null, null);
        }
        if (sourceBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient", sourceBefore, null);
        }
        long sourceAfter = sourceBefore - request.amountMinor();
        updateWalletBalance(request.sourcePlayerId(), sourceAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                sourceBefore, sourceAfter, null, null);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeTransfer(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception {
        Map<UUID, Long> balances = lockWalletBalances(request.sourcePlayerId(), request.targetPlayerId());
        Long sourceBefore = balances.get(request.sourcePlayerId());
        Long targetBefore = balances.get(request.targetPlayerId());
        if (sourceBefore == null || targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "A referenced wallet account does not exist", sourceBefore, targetBefore);
        }
        if (sourceBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient", sourceBefore, targetBefore);
        }
        long targetAfter;
        try {
            targetAfter = Math.addExact(targetBefore, request.amountMinor());
        } catch (ArithmeticException exception) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The target wallet balance cannot be represented", sourceBefore, targetBefore);
        }
        if (exceedsMaximum(targetAfter, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum", sourceBefore, targetBefore);
        }
        long sourceAfter = sourceBefore - request.amountMinor();
        updateWalletBalance(request.sourcePlayerId(), sourceAfter);
        updateWalletBalance(request.targetPlayerId(), targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                sourceBefore, sourceAfter, targetBefore, targetAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeSet(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception {
        Long targetBefore = readWalletBalanceMinor(request.targetPlayerId(), true);
        if (targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist", null, null);
        }
        long targetAfter = request.targetBalanceMinor();
        if (exceedsMaximum(targetAfter, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum", null, targetBefore);
        }
        long appliedAmount;
        try {
            appliedAmount = Math.abs(Math.subtractExact(targetAfter, targetBefore));
        } catch (ArithmeticException exception) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The balance change cannot be represented", null, targetBefore);
        }
        updateWalletBalance(request.targetPlayerId(), targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, appliedAmount,
                null, null, targetBefore, targetAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeWalletToBank(WalletTransactionRequest request, long walletBefore,
                                                   long bankBefore) throws Exception {
        if (walletBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The wallet account has insufficient funds for the bank deposit", walletBefore, null);
        }
        long bankAfter;
        try {
            bankAfter = Math.addExact(bankBefore, request.amountMinor());
        } catch (ArithmeticException exception) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting bank balance cannot be represented", walletBefore, null);
        }
        long walletAfter = walletBefore - request.amountMinor();
        updateWalletBalance(request.sourcePlayerId(), walletAfter);
        updateBankBalance(request.sourcePlayerId(), bankAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), walletBefore,
                walletAfter, null, null);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeBankToWallet(WalletTransactionRequest request, long walletBefore,
                                                   long bankBefore, long maximumBalanceMinor) throws Exception {
        if (bankBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The bank account has insufficient funds for the withdrawal", null, walletBefore);
        }
        long walletAfter;
        try {
            walletAfter = Math.addExact(walletBefore, request.amountMinor());
        } catch (ArithmeticException exception) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting wallet balance cannot be represented", null, walletBefore);
        }
        if (exceedsMaximum(walletAfter, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The wallet balance would exceed the configured maximum", null, walletBefore);
        }
        long bankAfter = bankBefore - request.amountMinor();
        updateBankBalance(request.targetPlayerId(), bankAfter);
        updateWalletBalance(request.targetPlayerId(), walletAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), null, null,
                walletBefore, walletAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord persistFailure(WalletTransactionRequest request, TransactionFailureReason reason,
                                             String detail, Long sourceBefore, Long targetBefore) throws Exception {
        TransactionRecord record = TransactionRecords.failed(request, reason, detail, sourceBefore, targetBefore);
        insertTransaction(record);
        return record;
    }

    private Map<UUID, Long> lockWalletBalances(UUID first, UUID second) throws Exception {
        List<UUID> identifiers = new ArrayList<>(List.of(first, second));
        identifiers.sort(Comparator.naturalOrder());
        Map<UUID, Long> values = new HashMap<>();
        for (UUID identifier : identifiers) {
            values.put(identifier, readWalletBalanceMinor(identifier, true));
        }
        return values;
    }

    /** Locks complete account rows in a stable order for the legacy low-level transfer adapters. */
    private Map<UUID, AccountBalances> lockAccountBalances(UUID first, UUID second) throws Exception {
        List<UUID> identifiers = new ArrayList<>();
        identifiers.add(first);
        if (!first.equals(second)) {
            identifiers.add(second);
        }
        identifiers.sort(Comparator.naturalOrder());
        Map<UUID, AccountBalances> values = new HashMap<>();
        for (UUID identifier : identifiers) {
            values.put(identifier, readAccountBalances(identifier, true));
        }
        return values;
    }

    private Long readWalletBalanceMinor(UUID playerId, boolean forUpdate) throws Exception {
        return readAccountBalanceMinor(playerId, WALLET_BALANCE_MINOR_COLUMN, forUpdate);
    }

    private Long readBankBalanceMinor(UUID playerId, boolean forUpdate) throws Exception {
        return readAccountBalanceMinor(playerId, BANK_BALANCE_MINOR_COLUMN, forUpdate);
    }

    private Long readAccountBalanceMinor(UUID playerId, String minorColumn, boolean forUpdate) throws Exception {
        String sql = "SELECT " + minorColumn + " FROM balances WHERE uuid=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                long balance = rows.getLong(1);
                if (rows.wasNull() || balance < 0L) {
                    throw new SQLException("The " + minorColumn + " balance is invalid for " + playerId);
                }
                return balance;
            }
        }
    }

    private AccountBalances readAccountBalances(UUID playerId, boolean forUpdate) throws Exception {
        String sql = "SELECT " + WALLET_BALANCE_MINOR_COLUMN + "," + BANK_BALANCE_MINOR_COLUMN
                + " FROM balances WHERE uuid=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Long walletMinor = readNullableLong(rows, WALLET_BALANCE_MINOR_COLUMN);
                Long bankMinor = readNullableLong(rows, BANK_BALANCE_MINOR_COLUMN);
                if (walletMinor == null || bankMinor == null) {
                    throw new SQLException("The account balance columns are null for " + playerId);
                }
                validateNonNegativeMinor(walletMinor, WALLET_BALANCE_MINOR_COLUMN, playerId);
                validateNonNegativeMinor(bankMinor, BANK_BALANCE_MINOR_COLUMN, playerId);
                return new AccountBalances(walletMinor, bankMinor);
            }
        }
    }

    private void updateWalletBalance(UUID playerId, long balanceMinor) throws Exception {
        updateAccountBalance(playerId, WALLET_BALANCE_MINOR_COLUMN, WALLET_BALANCE_COLUMN, balanceMinor);
    }

    private void updateBankBalance(UUID playerId, long balanceMinor) throws Exception {
        updateAccountBalance(playerId, BANK_BALANCE_MINOR_COLUMN, BANK_BALANCE_COLUMN, balanceMinor);
    }

    private void updateAccountBalance(UUID playerId, String minorColumn, String legacyColumn, long balanceMinor)
            throws Exception {
        validateNonNegativeMinor(balanceMinor, minorColumn, playerId);
        String sql = "UPDATE balances SET " + minorColumn + "=?, " + legacyColumn + "=? WHERE uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, balanceMinor);
            statement.setDouble(2, Money.fromMinorUnits(balanceMinor));
            statement.setString(3, playerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("The account disappeared during a transaction");
            }
        }
    }

    private Optional<TransactionRecord> findExistingRequest(WalletTransactionRequest request) throws Exception {
        Optional<TransactionRecord> sameId = findTransactionInternal(request.id());
        if (sameId.isPresent()) {
            return sameId;
        }
        return request.idempotencyKey() == null ? Optional.empty() : findTransactionByIdempotencyKey(request.idempotencyKey());
    }

    private Optional<TransactionRecord> findTransactionByIdempotencyKey(String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " WHERE idempotency_key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readTransaction(rows)) : Optional.empty();
            }
        }
    }

    private boolean hasSuccessfulReversal(UUID transactionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + TRANSACTIONS_TABLE
                + " WHERE successful_reversal_of_id=? LIMIT 1")) {
            statement.setString(1, transactionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void insertTransaction(TransactionRecord record) throws Exception {
        String sql = "INSERT INTO " + TRANSACTIONS_TABLE + "(id, timestamp_epoch_millis, transaction_type, transaction_status, "
                + "origin_type, origin_player_id, origin_identifier, wallet_operation, source_player_id, target_player_id, "
                + "requested_amount_minor, applied_amount_minor, source_balance_before_minor, source_balance_after_minor, "
                + "target_balance_before_minor, target_balance_after_minor, reason, metadata, reversal_of_transaction_id, "
                + "successful_reversal_of_id, batch_id, idempotency_key, failure_reason, failure_detail) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.id().toString());
            statement.setLong(2, record.timestamp().toEpochMilli());
            statement.setString(3, record.type().name());
            statement.setString(4, record.status().name());
            statement.setString(5, record.origin().type().name());
            setUuid(statement, 6, record.origin().playerId());
            statement.setString(7, record.origin().identifier());
            statement.setString(8, record.operation().name());
            setUuid(statement, 9, record.sourcePlayerId());
            setUuid(statement, 10, record.targetPlayerId());
            statement.setLong(11, record.requestedAmountMinor());
            statement.setLong(12, record.appliedAmountMinor());
            setLong(statement, 13, record.sourceBalanceBeforeMinor());
            setLong(statement, 14, record.sourceBalanceAfterMinor());
            setLong(statement, 15, record.targetBalanceBeforeMinor());
            setLong(statement, 16, record.targetBalanceAfterMinor());
            statement.setString(17, record.reason());
            statement.setString(18, TransactionMetadataCodec.encode(record.metadata()));
            setUuid(statement, 19, record.reversalOfTransactionId());
            setUuid(statement, 20, record.isSuccessful() ? record.reversalOfTransactionId() : null);
            setUuid(statement, 21, record.batchId());
            if (record.idempotencyKey() == null) statement.setNull(22, java.sql.Types.VARCHAR);
            else statement.setString(22, record.idempotencyKey());
            statement.setString(23, record.failureReason().name());
            if (record.failureDetail() == null) statement.setNull(24, java.sql.Types.VARCHAR);
            else statement.setString(24, record.failureDetail());
            statement.executeUpdate();
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static boolean exceedsMaximum(long balanceMinor, long maximumBalanceMinor) {
        return maximumBalanceMinor > 0L && balanceMinor > maximumBalanceMinor;
    }

    private static void validateWalletBankRequest(WalletTransactionRequest request, boolean mainToBank) {
        boolean valid = mainToBank
                ? request.operation() == WalletOperation.DEBIT && request.sourcePlayerId() != null
                && request.targetPlayerId() == null
                : request.operation() == WalletOperation.CREDIT && request.targetPlayerId() != null
                && request.sourcePlayerId() == null;
        if (!valid) {
            throw new IllegalArgumentException("The wallet-bank request operation does not match its direction");
        }
    }

    /** Adds baseline entries for pre-ledger balances exactly once. */
    private void bootstrapLegacyOpeningBalances() throws Exception {
        inTransaction(() -> {
            bootstrapLegacyOpeningBalancesInTransaction();
            return null;
        });
    }

    private void bootstrapLegacyOpeningBalancesInTransaction() throws Exception {
        if (hasAnyWalletTransaction()) {
            return;
        }
        List<LegacyBalance> balances = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid," + WALLET_BALANCE_MINOR_COLUMN + " FROM balances WHERE "
                        + WALLET_BALANCE_MINOR_COLUMN + " > 0 FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID playerId = UUID.fromString(rows.getString("uuid"));
                    long balanceMinor = rows.getLong(WALLET_BALANCE_MINOR_COLUMN);
                    if (!rows.wasNull() && balanceMinor > 0L) {
                        balances.add(new LegacyBalance(playerId, balanceMinor));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Invalid legacy rows are preserved but cannot be represented in the typed ledger.
                }
            }
        }
        Instant timestamp = Instant.now();
        for (LegacyBalance balance : balances) {
            insertTransaction(TransactionRecords.legacyOpeningBalance(balance.playerId(), balance.amountMinor(), timestamp));
        }
    }

    private boolean hasAnyWalletTransaction() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + TRANSACTIONS_TABLE + " LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            return rows.next();
        }
    }

    @Override
    public synchronized Optional<TransactionRecord> findTransaction(UUID transactionId) throws Exception {
        if (transactionId == null) {
            return Optional.empty();
        }
        ensureConnected();
        return findTransactionInternal(transactionId);
    }

    private Optional<TransactionRecord> findTransactionInternal(UUID transactionId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " WHERE id=?")) {
            statement.setString(1, transactionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readTransaction(rows)) : Optional.empty();
            }
        }
    }

    @Override
    public synchronized TransactionPage findTransactions(TransactionQuery query) throws Exception {
        Objects.requireNonNull(query, "query");
        ensureConnected();
        StringBuilder sql = new StringBuilder("SELECT ").append(TRANSACTION_COLUMNS).append(" FROM ")
                .append(TRANSACTIONS_TABLE).append(" WHERE 1=1");
        List<Object> values = new ArrayList<>();
        if (query.transactionId() != null) {
            sql.append(" AND id=?"); values.add(query.transactionId().toString());
        }
        if (query.playerId() != null) {
            sql.append(" AND (source_player_id=? OR target_player_id=?)");
            values.add(query.playerId().toString()); values.add(query.playerId().toString());
        }
        if (query.sourcePlayerId() != null) {
            sql.append(" AND source_player_id=?"); values.add(query.sourcePlayerId().toString());
        }
        if (query.targetPlayerId() != null) {
            sql.append(" AND target_player_id=?"); values.add(query.targetPlayerId().toString());
        }
        if (query.type() != null) {
            sql.append(" AND transaction_type=?"); values.add(query.type().name());
        }
        if (query.status() != null) {
            sql.append(" AND transaction_status=?"); values.add(query.status().name());
        }
        if (query.fromInclusive() != null) {
            sql.append(" AND timestamp_epoch_millis>=?"); values.add(query.fromInclusive().toEpochMilli());
        }
        if (query.toExclusive() != null) {
            sql.append(" AND timestamp_epoch_millis<?"); values.add(query.toExclusive().toEpochMilli());
        }
        sql.append(" ORDER BY timestamp_epoch_millis DESC, id DESC LIMIT ? OFFSET ?");
        values.add(query.limit() + 1); values.add(query.offset());
        List<TransactionRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < values.size(); index++) {
                Object value = values.get(index);
                if (value instanceof Long number) statement.setLong(index + 1, number);
                else if (value instanceof Integer number) statement.setInt(index + 1, number);
                else statement.setString(index + 1, String.valueOf(value));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) records.add(readTransaction(rows));
            }
        }
        boolean hasMore = records.size() > query.limit();
        if (hasMore) records.remove(records.size() - 1);
        return new TransactionPage(records, query.offset(), query.limit(), hasMore);
    }

    @Override
    public synchronized List<TransactionRecord> getAllWalletTransactions() throws Exception {
        ensureConnected();
        List<TransactionRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " ORDER BY timestamp_epoch_millis ASC, id ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) records.add(readTransaction(rows));
        }
        return List.copyOf(records);
    }

    @Override
    public synchronized EconomyStatistics getEconomyStatistics() throws Exception {
        return getEconomyAnalytics().statistics();
    }

    @Override
    public synchronized EconomyAnalytics getEconomyAnalytics() throws Exception {
        ensureConnected();
        WalletBalances balances = readWalletBalances();
        long totalTransactions = 0L;
        long successfulTransactions = 0L;
        long failedTransactions = 0L;
        long cancelledTransactions = 0L;
        long totalCredits = 0L;
        long totalDebits = 0L;
        long largestTransaction = 0L;
        long transferVolume = 0L;
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                totalTransactions = Math.addExact(totalTransactions, 1L);
                TransactionRecord record = readTransaction(rows);
                switch (record.status()) {
                    case SUCCESS -> {
                        successfulTransactions = Math.addExact(successfulTransactions, 1L);
                        long[] flows = TransactionRecords.moneySupplyFlows(record);
                        totalCredits = Math.addExact(totalCredits, flows[0]);
                        totalDebits = Math.addExact(totalDebits, flows[1]);
                        largestTransaction = Math.max(largestTransaction, record.appliedAmountMinor());
                        transferVolume = Math.addExact(transferVolume, TransactionRecords.transferVolume(record));
                    }
                    case FAILED -> failedTransactions = Math.addExact(failedTransactions, 1L);
                    case CANCELLED -> cancelledTransactions = Math.addExact(cancelledTransactions, 1L);
                }
            }
        } catch (ArithmeticException exception) {
            throw new SQLException("Wallet economy statistics exceed the supported numeric range", exception);
        }
        EconomyStatistics statistics = new EconomyStatistics(balances.accountCount(), balances.totalMinor(),
                balances.richestPlayerId(), balances.richestMinor(), totalTransactions, successfulTransactions,
                failedTransactions, cancelledTransactions, totalCredits, totalDebits);
        return new EconomyAnalytics(statistics, largestTransaction, transferVolume);
    }

    @Override
    public synchronized PlayerTransactionStatistics getPlayerTransactionStatistics(UUID playerId) throws Exception {
        if (playerId == null) {
            throw new IllegalArgumentException("The player ID must not be null");
        }
        ensureConnected();
        List<TransactionRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " WHERE source_player_id=? OR target_player_id=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    records.add(readTransaction(rows));
                }
            }
        }
        return PlayerTransactionStatistics.fromRecords(playerId, records);
    }

    private WalletBalances readWalletBalances() throws Exception {
        long accountCount = 0L;
        long totalMinor = 0L;
        UUID richestPlayerId = null;
        long richestMinor = 0L;
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, " + WALLET_BALANCE_MINOR_COLUMN
                + " FROM balances");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(rows.getString("uuid"));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new SQLException("Balances contains an invalid player UUID", exception);
                }
                long balanceMinor = rows.getLong(WALLET_BALANCE_MINOR_COLUMN);
                if (rows.wasNull() || balanceMinor < 0L) {
                    throw new SQLException("Balances contains an invalid wallet balance for " + playerId);
                }
                try {
                    accountCount = Math.addExact(accountCount, 1L);
                    totalMinor = Math.addExact(totalMinor, balanceMinor);
                } catch (ArithmeticException exception) {
                    throw new SQLException("Wallet balance totals exceed the supported numeric range", exception);
                }
                if (richestPlayerId == null || balanceMinor > richestMinor
                        || (balanceMinor == richestMinor
                        && playerId.toString().compareTo(richestPlayerId.toString()) < 0)) {
                    richestPlayerId = playerId;
                    richestMinor = balanceMinor;
                }
            }
        }
        return new WalletBalances(accountCount, totalMinor, richestPlayerId, richestMinor);
    }

    private PlayerIdentity readPlayerIdentity(ResultSet rows) throws SQLException {
        try {
            UUID playerId = readRequiredUuid(rows, "uuid", PLAYER_IDENTITIES_TABLE);
            return new PlayerIdentity(playerId, rows.getString("last_known_name"),
                    rows.getString("normalized_name"), Instant.ofEpochMilli(rows.getLong("last_login_epoch_millis")),
                    readEnum(PlayerType.class, rows.getString("player_type"), "player type"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("Player identity registry contains an invalid record", exception);
        }
    }

    private void upsertPlayerIdentityInternal(PlayerIdentity identity) throws SQLException {
        String sql = "INSERT INTO " + PLAYER_IDENTITIES_TABLE + "(uuid, last_known_name, normalized_name, "
                + "last_login_epoch_millis, player_type) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "last_known_name=IF(VALUES(last_login_epoch_millis)>=last_login_epoch_millis, "
                + "VALUES(last_known_name), last_known_name), "
                + "normalized_name=IF(VALUES(last_login_epoch_millis)>=last_login_epoch_millis, "
                + "VALUES(normalized_name), normalized_name), "
                + "last_login_epoch_millis=GREATEST(last_login_epoch_millis, VALUES(last_login_epoch_millis)), "
                + "player_type=CASE WHEN player_type='BEDROCK' OR VALUES(player_type)='BEDROCK' THEN 'BEDROCK' "
                + "WHEN VALUES(player_type)<>'UNKNOWN' THEN VALUES(player_type) ELSE player_type END";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identity.playerId().toString());
            statement.setString(2, identity.lastKnownName());
            statement.setString(3, identity.normalizedName());
            statement.setLong(4, identity.lastLoginAt().toEpochMilli());
            statement.setString(5, identity.playerType().name());
            statement.executeUpdate();
        }
    }

    private TransactionRecord readTransaction(ResultSet rows) throws SQLException {
        try {
            UUID id = readUuid(rows, "id");
            if (id == null) {
                throw new SQLException("Wallet journal contains a null transaction ID");
            }
            TransactionStatus status = readEnum(TransactionStatus.class, rows.getString("transaction_status"),
                    "transaction status");
            TransactionOrigin origin = new TransactionOrigin(
                    readEnum(TransactionOriginType.class, rows.getString("origin_type"), "origin type"),
                    readUuid(rows, "origin_player_id"), rows.getString("origin_identifier"));
            return new TransactionRecord(id, Instant.ofEpochMilli(rows.getLong("timestamp_epoch_millis")),
                    readEnum(TransactionType.class, rows.getString("transaction_type"), "transaction type"), status, origin,
                    readEnum(WalletOperation.class, rows.getString("wallet_operation"), "wallet operation"),
                    readUuid(rows, "source_player_id"), readUuid(rows, "target_player_id"),
                    rows.getLong("requested_amount_minor"), rows.getLong("applied_amount_minor"),
                    readNullableLong(rows, "source_balance_before_minor"), readNullableLong(rows, "source_balance_after_minor"),
                    readNullableLong(rows, "target_balance_before_minor"), readNullableLong(rows, "target_balance_after_minor"),
                    rows.getString("reason"), TransactionMetadataCodec.decode(rows.getString("metadata")),
                    readUuid(rows, "reversal_of_transaction_id"), readUuid(rows, "batch_id"),
                    rows.getString("idempotency_key"),
                    readEnum(TransactionFailureReason.class, rows.getString("failure_reason"), "failure reason"),
                    rows.getString("failure_detail"));
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Wallet journal contains an invalid transaction record", exception);
        }
    }

    private static <T extends Enum<T>> T readEnum(Class<T> type, String value, String column) throws SQLException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("Wallet journal contains an invalid " + column, exception);
        }
    }

    private static UUID readUuid(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Wallet journal contains an invalid UUID in " + column, exception);
        }
    }

    private static UUID readRequiredUuid(ResultSet rows, String column, String table) throws SQLException {
        UUID value = readUuid(rows, column);
        if (value == null) {
            throw new SQLException(table + " contains a null UUID in " + column);
        }
        return value;
    }

    private static Long readNullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void validateNonNegativeMinor(long amountMinor, String column, UUID playerId) throws SQLException {
        if (amountMinor < 0L) {
            throw new SQLException("The " + column + " balance is negative for " + playerId);
        }
    }

    private record LegacyBalance(UUID playerId, long amountMinor) { }

    private record BalanceMigration(String storageKey, long walletMinor, long bankMinor) { }

    private record BankInterestUpdate(UUID playerId, long beforeMinor, long afterMinor) { }

    private record AccountBalances(long walletMinor, long bankMinor) {
        private long valueFor(String minorColumn) {
            if (WALLET_BALANCE_MINOR_COLUMN.equals(minorColumn)) {
                return walletMinor;
            }
            if (BANK_BALANCE_MINOR_COLUMN.equals(minorColumn)) {
                return bankMinor;
            }
            throw new IllegalArgumentException("Unsupported exact balance column: " + minorColumn);
        }
    }

    private record WalletBalances(long accountCount, long totalMinor, UUID richestPlayerId, long richestMinor) { }

    private static boolean isTransientConcurrencyFailure(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ("40001".equals(state) || "41000".equals(state)
                    || current.getErrorCode() == MYSQL_DEADLOCK_ERROR
                    || current.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT_ERROR) {
                return true;
            }
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt, SQLException cause) throws SQLException {
        long delayMillis = Math.min(25L * attempt, 100L);
        if (debug) {
            plugin.getLogger().warning("[Storage] Retrying transient MYSQL/MariaDB wallet transaction failure in "
                    + delayMillis + " ms (attempt " + attempt + "/" + MYSQL_TRANSACTION_RETRY_ATTEMPTS
                    + "): " + cause.getMessage());
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SQLException wrapped = new SQLException("Interrupted while retrying a transient MYSQL/MariaDB wallet transaction",
                    interrupted);
            wrapped.addSuppressed(cause);
            throw wrapped;
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) throws Exception {
        return inTransaction(operation, true);
    }

    private <T> T inTransaction(SqlOperation<T> operation, boolean enforceMutationGate) throws Exception {
        ensureConnected();
        Connection activeConnection = connection;
        boolean autoCommit = activeConnection.getAutoCommit();
        boolean started = false;
        Exception failure = null;
        try {
            activeConnection.setAutoCommit(false);
            transactionActive = true;
            started = true;
            if (enforceMutationGate) assertMutationsAllowed();
            T result = operation.run();
            activeConnection.commit();
            return result;
        } catch (Exception exception) {
            failure = exception;
            if (started) {
                try {
                    activeConnection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
            }
            throw exception;
        } finally {
            transactionActive = false;
            if (started) {
                try {
                    if (!activeConnection.isClosed()) activeConnection.setAutoCommit(autoCommit);
                } catch (SQLException resetFailure) {
                    if (failure != null) failure.addSuppressed(resetFailure);
                    else throw resetFailure;
                }
            }
        }
    }

    /** Visible to storage-package integration tests without changing the public storage API. */
    synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(1);
        } catch (SQLException ignored) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
            // Closing is best effort; no caller can recover a connection that is already broken.
        } finally {
            connection = null;
            transactionActive = false;
        }
    }

    @Override public boolean isDebug() { return debug; }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws Exception;
    }
}
