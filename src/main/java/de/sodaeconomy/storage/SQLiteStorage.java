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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite storage. A single synchronized connection serializes local mutations. Wallet balance
 * changes and journal entries share the same JDBC transaction, so they cannot be committed
 * independently.
 */
@SuppressWarnings("removal")
public class SQLiteStorage implements Storage, WalletTransactionStore {
    private static final int CURRENT_SCHEMA_VERSION = 4;
    private static final String SCHEMA_NAME = "sodaeconomy";
    private static final String SCHEMA_VERSION_TABLE = "sodaeconomy_schema_version";
    private static final String BALANCES_TABLE = "balances";
    private static final String WALLET_BALANCE_COLUMN = "balance";
    private static final String BANK_BALANCE_COLUMN = "bank_balance";
    private static final String WALLET_BALANCE_MINOR_COLUMN = "balance_minor";
    private static final String BANK_BALANCE_MINOR_COLUMN = "bank_balance_minor";
    private static final String BALANCE_VERSION_COLUMN = "balance_version";
    private static final String TRANSACTIONS_TABLE = "wallet_transactions";
    private static final String PLAYER_IDENTITIES_TABLE = "player_identities";
    private static final String TRANSACTION_COLUMNS = "id, timestamp_epoch_millis, transaction_type, transaction_status, "
            + "origin_type, origin_player_id, origin_identifier, wallet_operation, source_player_id, target_player_id, "
            + "requested_amount_minor, applied_amount_minor, source_balance_before_minor, source_balance_after_minor, "
            + "target_balance_before_minor, target_balance_after_minor, reason, metadata, reversal_of_transaction_id, "
            + "batch_id, idempotency_key, failure_reason, failure_detail";

    private Connection connection;
    private File dbFile;
    private boolean debug;
    private boolean transactionActive;

    @Override
    public synchronized void init(JavaPlugin plugin) throws Exception {
        close();
        debug = plugin.getConfig().getBoolean("debug.storage", false);
        File folder = new File(plugin.getDataFolder(), "storage");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Cannot create storage directory");
        }
        dbFile = new File(folder, "economy.db");

        // sqlite-jdbc must retain its original package because its native JNI symbols use org.sqlite.
        Class.forName(org.sqlite.JDBC.class.getName());
        ensureConnected();
    }

    private void ensureConnected() throws Exception {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        if (transactionActive) {
            throw new SQLException("SQLite connection was lost during an active transaction");
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try {
            configureConnection();
            ensureSchema();
            bootstrapLegacyOpeningBalances();
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private void ensureSchema() throws Exception {
        ensureSchemaVersionTable();
        int version = readSchemaVersion();
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new SQLException("SQLite schema version " + version + " is newer than supported version "
                    + CURRENT_SCHEMA_VERSION);
        }

        while (version < CURRENT_SCHEMA_VERSION) {
            if (version == 0) {
                migrateToVersionOne();
                version = 1;
                } else if (version == 1) {
                    migrateToVersionTwo();
                    version = 2;
                } else if (version == 2) {
                    migrateToVersionThree();
                    version = 3;
                } else if (version == 3) {
                    migrateToVersionFour();
                    version = 4;
                } else {
                    throw new SQLException("No SQLite migration is available from schema version " + version);
                }
        }

        // A previously interrupted legacy installation may contain the version row without the
        // table. Creating the table and indexes again is harmless and restores the invariant.
        ensureWalletTransactionsTable();
        ensureWalletTransactionIndexes();
        ensurePlayerIdentitiesTable();
        ensureCanonicalBalanceColumnsPresent();
    }

    private void ensureSchemaVersionTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + SCHEMA_VERSION_TABLE
                    + " (schema_name TEXT PRIMARY KEY, version INTEGER NOT NULL)");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO " + SCHEMA_VERSION_TABLE
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
                if (!rows.next()) {
                    throw new SQLException("Could not read the SodaEconomy SQLite schema version");
                }
                return rows.getInt(1);
            }
        }
    }

    private void migrateToVersionOne() throws Exception {
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS balances (uuid TEXT PRIMARY KEY, "
                        + "balance REAL NOT NULL, bank_balance REAL NOT NULL DEFAULT 0)");
            }
            if (!hasColumn("balances", "bank_balance")) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("ALTER TABLE balances ADD COLUMN bank_balance REAL NOT NULL DEFAULT 0");
                }
            }
            updateSchemaVersion(1);
            return null;
        });
    }

    private void migrateToVersionTwo() throws Exception {
        inTransaction(() -> {
            ensureWalletTransactionsTable();
            ensureWalletTransactionIndexes();
            updateSchemaVersion(2);
            return null;
        });
    }

    /**
     * Adds exact integer balance columns while retaining the legacy REAL columns as mirrors for
     * tooling that still reads the old schema. The complete backfill and version update run in one
     * SQLite transaction, so an interrupted upgrade remains safely retryable as schema version 2.
     */
    private void migrateToVersionThree() throws Exception {
        inTransaction(() -> {
            ensureCanonicalBalanceColumns();
            backfillCanonicalBalanceColumns();
            updateSchemaVersion(3);
            return null;
        });
    }

    /** Adds the durable UUID-to-name registry used by the central identity service. */
    private void migrateToVersionFour() throws Exception {
        inTransaction(() -> {
            ensurePlayerIdentitiesTable();
            updateSchemaVersion(4);
            return null;
        });
    }

    private void ensureCanonicalBalanceColumns() throws SQLException {
        if (!hasColumn(BALANCES_TABLE, WALLET_BALANCE_MINOR_COLUMN)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + BALANCES_TABLE + " ADD COLUMN "
                        + WALLET_BALANCE_MINOR_COLUMN + " INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (!hasColumn(BALANCES_TABLE, BANK_BALANCE_MINOR_COLUMN)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + BALANCES_TABLE + " ADD COLUMN "
                        + BANK_BALANCE_MINOR_COLUMN + " INTEGER NOT NULL DEFAULT 0");
            }
        }
        if (!hasColumn(BALANCES_TABLE, BALANCE_VERSION_COLUMN)) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + BALANCES_TABLE + " ADD COLUMN "
                        + BALANCE_VERSION_COLUMN + " INTEGER NOT NULL DEFAULT 0");
            }
        }
    }

    private void ensureCanonicalBalanceColumnsPresent() throws SQLException {
        if (!hasColumn(BALANCES_TABLE, WALLET_BALANCE_MINOR_COLUMN)
                || !hasColumn(BALANCES_TABLE, BANK_BALANCE_MINOR_COLUMN)
                || !hasColumn(BALANCES_TABLE, BALANCE_VERSION_COLUMN)) {
            throw new SQLException("SQLite schema version 3 is missing canonical balance columns");
        }
    }

    private void backfillCanonicalBalanceColumns() throws SQLException {
        String selectSql = "SELECT uuid, " + WALLET_BALANCE_COLUMN + ", " + BANK_BALANCE_COLUMN
                + " FROM " + BALANCES_TABLE;
        String updateSql = "UPDATE " + BALANCES_TABLE + " SET " + WALLET_BALANCE_COLUMN + "=?, "
                + BANK_BALANCE_COLUMN + "=?, " + WALLET_BALANCE_MINOR_COLUMN + "=?, "
                + BANK_BALANCE_MINOR_COLUMN + "=?, " + BALANCE_VERSION_COLUMN + "=0 WHERE uuid=?";
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            while (rows.next()) {
                String accountId = rows.getString("uuid");
                long walletMinor = readLegacyBalanceMinor(rows, WALLET_BALANCE_COLUMN, accountId);
                long bankMinor = readLegacyBalanceMinor(rows, BANK_BALANCE_COLUMN, accountId);
                update.setDouble(1, Money.fromMinorUnits(walletMinor));
                update.setDouble(2, Money.fromMinorUnits(bankMinor));
                update.setLong(3, walletMinor);
                update.setLong(4, bankMinor);
                update.setString(5, accountId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Could not backfill canonical balances for " + accountId);
                }
            }
        }
    }

    private long readLegacyBalanceMinor(ResultSet rows, String column, String accountId) throws SQLException {
        double value = rows.getDouble(column);
        if (rows.wasNull() || !Money.isValid(value) || value < 0D) {
            throw new SQLException("Legacy account " + accountId + " contains an invalid " + column + " balance");
        }
        try {
            return Money.toMinorUnits(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Legacy account " + accountId + " contains an out-of-range " + column
                    + " balance", exception);
        }
    }

    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) {
                if (column.equalsIgnoreCase(rows.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void updateSchemaVersion(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + SCHEMA_VERSION_TABLE
                + " SET version=? WHERE schema_name=?")) {
            statement.setInt(1, version);
            statement.setString(2, SCHEMA_NAME);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not update the SodaEconomy SQLite schema version");
            }
        }
    }

    private void ensurePlayerIdentitiesTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + PLAYER_IDENTITIES_TABLE + " ("
                    + "uuid TEXT PRIMARY KEY, last_known_name TEXT NOT NULL, normalized_name TEXT NOT NULL, "
                    + "last_login_epoch_millis INTEGER NOT NULL, player_type TEXT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_player_identities_normalized_name ON "
                    + PLAYER_IDENTITIES_TABLE + "(normalized_name)");
        }
    }

    private void ensureWalletTransactionsTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + TRANSACTIONS_TABLE + " ("
                    + "id TEXT PRIMARY KEY, "
                    + "timestamp_epoch_millis INTEGER NOT NULL, "
                    + "transaction_type TEXT NOT NULL, "
                    + "transaction_status TEXT NOT NULL, "
                    + "origin_type TEXT NOT NULL, "
                    + "origin_player_id TEXT, "
                    + "origin_identifier TEXT NOT NULL, "
                    + "wallet_operation TEXT NOT NULL, "
                    + "source_player_id TEXT, "
                    + "target_player_id TEXT, "
                    + "requested_amount_minor INTEGER NOT NULL, "
                    + "applied_amount_minor INTEGER NOT NULL, "
                    + "source_balance_before_minor INTEGER, "
                    + "source_balance_after_minor INTEGER, "
                    + "target_balance_before_minor INTEGER, "
                    + "target_balance_after_minor INTEGER, "
                    + "reason TEXT NOT NULL, "
                    + "metadata TEXT NOT NULL, "
                    + "reversal_of_transaction_id TEXT, "
                    + "successful_reversal_of_id TEXT UNIQUE, "
                    + "batch_id TEXT, "
                    + "idempotency_key TEXT UNIQUE, "
                    + "failure_reason TEXT NOT NULL, "
                    + "failure_detail TEXT)");
        }
    }

    private void ensureWalletTransactionIndexes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_transactions_source_timestamp ON "
                    + TRANSACTIONS_TABLE + "(source_player_id, timestamp_epoch_millis DESC, id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_transactions_target_timestamp ON "
                    + TRANSACTIONS_TABLE + "(target_player_id, timestamp_epoch_millis DESC, id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_transactions_timestamp ON "
                    + TRANSACTIONS_TABLE + "(timestamp_epoch_millis DESC, id DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_wallet_transactions_type_status_timestamp ON "
                    + TRANSACTIONS_TABLE + "(transaction_type, transaction_status, timestamp_epoch_millis DESC, id DESC)");
        }
    }

    /** Adds one opening entry per pre-ledger positive wallet balance only while the journal is empty. */
    private void bootstrapLegacyOpeningBalances() throws Exception {
        inTransaction(() -> {
            bootstrapLegacyOpeningBalancesInTransaction();
            return null;
        });
    }

    /** Requires an active transaction so snapshot imports can bootstrap without a second commit. */
    private void bootstrapLegacyOpeningBalancesInTransaction() throws Exception {
        if (hasAnyWalletTransaction()) {
            return;
        }

        List<LegacyBalance> balances = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, " + WALLET_BALANCE_MINOR_COLUMN
                + " FROM " + BALANCES_TABLE + " WHERE " + WALLET_BALANCE_MINOR_COLUMN + " > 0");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(rows.getString("uuid"));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("Legacy balances contains an invalid player UUID", exception);
                }
                long balanceMinor = rows.getLong(WALLET_BALANCE_MINOR_COLUMN);
                if (rows.wasNull() || balanceMinor <= 0L) {
                    throw new SQLException("Legacy balances contains an invalid positive wallet balance for " + playerId);
                }
                balances.add(new LegacyBalance(playerId, balanceMinor));
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
    public synchronized Double getBalance(UUID uuid) throws Exception {
        return getValue(AccountBalanceColumn.WALLET, uuid);
    }

    @Override
    public synchronized Long getBalanceMinorUnits(UUID uuid) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.getBalanceMinorUnits", SQLiteStorage.class);
        Objects.requireNonNull(uuid, "uuid");
        ensureConnected();
        return readAccountBalanceMinor(uuid, AccountBalanceColumn.WALLET);
    }

    @Override
    public synchronized double getOrCreateBalance(UUID uuid, double initial) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.getOrCreateBalance", SQLiteStorage.class);
        long initialMinor = Money.toMinorUnits(initial);
        return Money.fromMinorUnits(ensureWalletAccount(uuid, initialMinor, Instant.now()).balanceMinor());
    }

    @Override
    public synchronized WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp)
            throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.ensureWalletAccount", SQLiteStorage.class);
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(timestamp, "timestamp");
        if (startingBalanceMinor < 0) {
            throw new IllegalArgumentException("The starting wallet balance must not be negative");
        }

        return inTransaction(() -> {
            Long existingBalance = readWalletBalanceMinor(playerId);
            if (existingBalance != null) {
                return new WalletAccountState(existingBalance, false);
            }

            try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO " + BALANCES_TABLE
                    + "(uuid, balance, bank_balance, balance_minor, bank_balance_minor, balance_version) VALUES(?, ?, 0, ?, 0, 0)")) {
                insert.setString(1, playerId.toString());
                insert.setDouble(2, Money.fromMinorUnits(startingBalanceMinor));
                insert.setLong(3, startingBalanceMinor);
                if (insert.executeUpdate() == 1) {
                    insertTransaction(TransactionRecords.initialBalance(playerId, startingBalanceMinor, timestamp));
                    return new WalletAccountState(startingBalanceMinor, true);
                }
            }

            Long concurrentlyCreatedBalance = readWalletBalanceMinor(playerId);
            if (concurrentlyCreatedBalance == null) {
                throw new SQLException("Could not create economy account");
            }
            return new WalletAccountState(concurrentlyCreatedBalance, false);
        });
    }

    @Override
    public synchronized void setBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.setBalance", SQLiteStorage.class);
        setValue(AccountBalanceColumn.WALLET, uuid, amount);
    }

    @Override
    public synchronized Map<UUID, PlayerIdentity> getAllPlayerIdentities() throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.getAllPlayerIdentities", SQLiteStorage.class);
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
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.findPlayerIdentity", SQLiteStorage.class);
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
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.findPlayerIdentitiesByNormalizedName", SQLiteStorage.class);
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
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.findPlayerIdentities", SQLiteStorage.class);
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
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.upsertPlayerIdentity", SQLiteStorage.class);
        Objects.requireNonNull(identity, "identity");
        inTransaction(() -> {
            upsertPlayerIdentityInternal(identity);
            return null;
        });
    }

    @Override
    public synchronized void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.replacePlayerIdentities", SQLiteStorage.class);
        Objects.requireNonNull(identities, "identities");
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + PLAYER_IDENTITIES_TABLE);
            }
            for (PlayerIdentity identity : identities.values()) upsertPlayerIdentityInternal(identity);
            return null;
        });
    }

    @Override
    public synchronized Double getBankBalance(UUID uuid) throws Exception {
        return getValue(AccountBalanceColumn.BANK, uuid);
    }

    @Override
    public synchronized void setBankBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.setBankBalance", SQLiteStorage.class);
        setValue(AccountBalanceColumn.BANK, uuid, amount);
    }

    private Double getValue(AccountBalanceColumn column, UUID uuid) throws Exception {
        Objects.requireNonNull(uuid, "uuid");
        ensureConnected();
        Long amountMinor = readAccountBalanceMinor(uuid, column);
        return amountMinor == null ? null : Money.fromMinorUnits(amountMinor);
    }

    private void setValue(AccountBalanceColumn column, UUID uuid, double amount) throws Exception {
        Objects.requireNonNull(uuid, "uuid");
        long amountMinor = Money.toMinorUnits(amount);
        if (amountMinor < 0L) {
            throw new IllegalArgumentException("Account balances must not be negative");
        }
        ensureConnected();
        String sql = "INSERT INTO " + BALANCES_TABLE + "(uuid, balance, bank_balance, balance_minor, bank_balance_minor, balance_version) "
                + "VALUES(?, ?, ?, ?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET "
                + column.legacyColumn + "=excluded." + column.legacyColumn + ", "
                + column.minorColumn + "=excluded." + column.minorColumn + ", "
                + BALANCE_VERSION_COLUMN + "=" + BALANCES_TABLE + "." + BALANCE_VERSION_COLUMN + "+1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setDouble(2, column == AccountBalanceColumn.WALLET ? Money.fromMinorUnits(amountMinor) : 0D);
            statement.setDouble(3, column == AccountBalanceColumn.BANK ? Money.fromMinorUnits(amountMinor) : 0D);
            statement.setLong(4, column == AccountBalanceColumn.WALLET ? amountMinor : 0L);
            statement.setLong(5, column == AccountBalanceColumn.BANK ? amountMinor : 0L);
            statement.executeUpdate();
        }
    }

    @Override
    public synchronized Map<UUID, Double> getAllBalances() throws Exception {
        return getAll(AccountBalanceColumn.WALLET);
    }

    @Override
    public synchronized Map<UUID, Double> getAllBankBalances() throws Exception {
        return getAll(AccountBalanceColumn.BANK);
    }

    @Override
    public synchronized Map<UUID, Long> getAllBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.getAllBalanceMinorUnits", SQLiteStorage.class);
        return getAllMinorUnits(AccountBalanceColumn.WALLET);
    }

    @Override
    public synchronized Map<UUID, Long> getAllBankBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.getAllBankBalanceMinorUnits", SQLiteStorage.class);
        return getAllMinorUnits(AccountBalanceColumn.BANK);
    }

    private Map<UUID, Double> getAll(AccountBalanceColumn column) throws Exception {
        Map<UUID, Long> minorBalances = getAllMinorUnits(column);
        Map<UUID, Double> result = new HashMap<>(minorBalances.size());
        for (Map.Entry<UUID, Long> entry : minorBalances.entrySet()) {
            result.put(entry.getKey(), Money.fromMinorUnits(entry.getValue()));
        }
        return result;
    }

    private Map<UUID, Long> getAllMinorUnits(AccountBalanceColumn column) throws Exception {
        ensureConnected();
        Map<UUID, Long> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid," + column.minorColumn + " FROM "
                + BALANCES_TABLE);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    long value = rows.getLong(2);
                    if (rows.wasNull() || value < 0L) {
                        throw new SQLException("Account " + rows.getString(1) + " contains an invalid "
                                + column.minorColumn + " balance");
                    }
                    result.put(UUID.fromString(rows.getString(1)), value);
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("The balances table contains an invalid account identifier", exception);
                }
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public synchronized void saveAll(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.saveAll", SQLiteStorage.class);
        saveAll(AccountBalanceColumn.WALLET, balances);
    }

    @Override
    public synchronized void saveAllBank(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.saveAllBank", SQLiteStorage.class);
        saveAll(AccountBalanceColumn.BANK, balances);
    }

    @Override
    public synchronized void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.replaceSnapshot", SQLiteStorage.class);
        Objects.requireNonNull(snapshot, "snapshot");
        replaceMinorUnitSnapshot(toMinorUnits(snapshot.balances()), toMinorUnits(snapshot.bankBalances()),
                snapshot.walletTransactions());
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
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.replaceMinorUnitSnapshot", SQLiteStorage.class);
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(balances, bankBalances, walletTransactions,
                playerIdentities);
        inTransaction(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + TRANSACTIONS_TABLE);
                statement.executeUpdate("DELETE FROM " + BALANCES_TABLE);
                statement.executeUpdate("DELETE FROM " + PLAYER_IDENTITIES_TABLE);
            }
            Map<UUID, Long> combinedBalances = new HashMap<>(snapshot.balances());
            for (UUID playerId : snapshot.bankBalances().keySet()) {
                combinedBalances.putIfAbsent(playerId, 0L);
            }
            for (Map.Entry<UUID, Long> entry : combinedBalances.entrySet()) {
                insertAccountBalances(entry.getKey(), entry.getValue(),
                        snapshot.bankBalances().getOrDefault(entry.getKey(), 0L));
            }
            for (TransactionRecord transaction : snapshot.walletTransactions()) {
                insertTransaction(transaction);
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

    @Override
    public synchronized void saveAllBankMinorUnits(Map<UUID, Long> bankBalances) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.saveAllBankMinorUnits", SQLiteStorage.class);
        saveAllMinorUnits(AccountBalanceColumn.BANK, bankBalances);
    }

    private void saveAll(AccountBalanceColumn column, Map<UUID, Double> values) throws Exception {
        Objects.requireNonNull(values, "values");
        Map<UUID, Long> minorValues = toMinorUnits(values);
        saveAllMinorUnits(column, minorValues);
    }

    private void saveAllMinorUnits(AccountBalanceColumn column, Map<UUID, Long> values) throws Exception {
        Objects.requireNonNull(values, "values");
        inTransaction(() -> {
            for (Map.Entry<UUID, Long> value : values.entrySet()) {
                if (value.getKey() == null || value.getValue() == null || value.getValue() < 0L) {
                    throw new IllegalArgumentException("Account balances must contain non-null, non-negative values");
                }
                writeOrCreateAccountBalanceMinor(value.getKey(), column, value.getValue());
            }
            return null;
        });
    }

    @Override
    public synchronized boolean transferMain(UUID source, UUID target, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.transferMain", SQLiteStorage.class);
        return transfer(source, AccountBalanceColumn.WALLET, target, AccountBalanceColumn.WALLET, amount);
    }

    @Override
    public synchronized boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.transferMainAndBank", SQLiteStorage.class);
        return mainToBank ? transfer(uuid, AccountBalanceColumn.WALLET, uuid, AccountBalanceColumn.BANK, amount)
                : transfer(uuid, AccountBalanceColumn.BANK, uuid, AccountBalanceColumn.WALLET, amount);
    }

    private boolean transfer(UUID source, AccountBalanceColumn sourceColumn, UUID target, AccountBalanceColumn targetColumn,
                             double amount) throws Exception {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        long amountMinor = Money.toMinorUnits(amount);
        if (amountMinor <= 0L) {
            return false;
        }
        if (sourceColumn == targetColumn && source.equals(target)) {
            return false;
        }
        return inTransaction(() -> {
            Long sourceBefore = readAccountBalanceMinor(source, sourceColumn);
            Long targetBefore = readAccountBalanceMinor(target, targetColumn);
            if (sourceBefore == null || targetBefore == null || sourceBefore < amountMinor) {
                return false;
            }
            final long targetAfter;
            try {
                targetAfter = Math.addExact(targetBefore, amountMinor);
            } catch (ArithmeticException exception) {
                return false;
            }
            writeAccountBalanceMinor(source, sourceColumn, sourceBefore - amountMinor);
            writeAccountBalanceMinor(target, targetColumn, targetAfter);
            return true;
        });
    }

    @Override
    public synchronized TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor)
            throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.executeWalletTransaction", SQLiteStorage.class);
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        ensureConnected();

        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return inTransaction(() -> executeWalletTransactionInTransaction(request, maximumBalanceMinor));
        } catch (SQLException exception) {
            // A second plugin process can win the unique idempotency or successful-reversal race
            // after the initial lookup. Return the already committed request where possible.
            Optional<TransactionRecord> concurrentRequest = findExistingRequest(request);
            if (concurrentRequest.isPresent()) {
                return concurrentRequest.get();
            }
            if (request.reversalOfTransactionId() != null && hasSuccessfulReversal(request.reversalOfTransactionId())) {
                return inTransaction(() -> persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back", null, null));
            }
            throw exception;
        }
    }

    @Override
    public synchronized TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request,
                                                                        boolean mainToBank,
                                                                        long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("SQLiteStorage.executeWalletBankTransaction", SQLiteStorage.class);
        Objects.requireNonNull(request, "request");
        if (maximumBalanceMinor < 0) {
            throw new IllegalArgumentException("The maximum wallet balance must not be negative");
        }
        validateWalletBankRequest(request, mainToBank);
        ensureConnected();

        Optional<TransactionRecord> existing = findExistingRequest(request);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return inTransaction(() -> executeWalletBankTransactionInTransaction(request, mainToBank,
                    maximumBalanceMinor));
        } catch (SQLException exception) {
            Optional<TransactionRecord> concurrentRequest = findExistingRequest(request);
            if (concurrentRequest.isPresent()) {
                return concurrentRequest.get();
            }
            if (request.reversalOfTransactionId() != null && hasSuccessfulReversal(request.reversalOfTransactionId())) {
                return inTransaction(() -> persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back", null, null));
            }
            throw exception;
        }
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
                        "The referenced transaction is not a successful transaction", null, null);
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
                        "The referenced transaction is not a successful transaction", null, null);
            }
            if (hasSuccessfulReversal(request.reversalOfTransactionId())) {
                return persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The referenced transaction has already been rolled back", null, null);
            }
        }

        UUID playerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
        Long walletBefore = readWalletBalanceMinor(playerId);
        Long bankBefore = readBankBalanceMinor(playerId);
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
        UUID target = request.targetPlayerId();
        Long targetBefore = readWalletBalanceMinor(target);
        if (targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist", null, null);
        }
        Long targetAfter = addWithinMaximum(targetBefore, request.amountMinor(), maximumBalanceMinor);
        if (targetAfter == null) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The credit would exceed the maximum wallet balance", null, targetBefore);
        }
        writeWalletBalanceMinor(target, targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), null, null,
                targetBefore, targetAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeDebit(WalletTransactionRequest request) throws Exception {
        UUID source = request.sourcePlayerId();
        Long sourceBefore = readWalletBalanceMinor(source);
        if (sourceBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The source wallet account does not exist", null, null);
        }
        if (sourceBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet account has insufficient funds", sourceBefore, null);
        }
        long sourceAfter = sourceBefore - request.amountMinor();
        writeWalletBalanceMinor(source, sourceAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), sourceBefore, sourceAfter,
                null, null);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeTransfer(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception {
        UUID source = request.sourcePlayerId();
        UUID target = request.targetPlayerId();
        Long sourceBefore = readWalletBalanceMinor(source);
        Long targetBefore = readWalletBalanceMinor(target);
        if (sourceBefore == null || targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "Both wallet accounts must exist before a transfer can be committed", sourceBefore, targetBefore);
        }
        if (sourceBefore < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet account has insufficient funds", sourceBefore, targetBefore);
        }
        Long targetAfter = addWithinMaximum(targetBefore, request.amountMinor(), maximumBalanceMinor);
        if (targetAfter == null) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The transfer would exceed the target wallet maximum balance", sourceBefore, targetBefore);
        }

        long sourceAfter = sourceBefore - request.amountMinor();
        writeWalletBalanceMinor(source, sourceAfter);
        writeWalletBalanceMinor(target, targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), sourceBefore, sourceAfter,
                targetBefore, targetAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord executeSet(WalletTransactionRequest request, long maximumBalanceMinor) throws Exception {
        UUID target = request.targetPlayerId();
        Long targetBefore = readWalletBalanceMinor(target);
        if (targetBefore == null) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The target wallet account does not exist", null, null);
        }
        long targetAfter = Objects.requireNonNull(request.targetBalanceMinor(), "targetBalanceMinor");
        if (maximumBalanceMinor > 0 && targetAfter > maximumBalanceMinor) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The requested wallet balance exceeds the configured maximum", null, targetBefore);
        }
        long appliedAmount = absoluteDifference(targetBefore, targetAfter);
        writeWalletBalanceMinor(target, targetAfter);
        TransactionRecord record = TransactionRecords.successful(request, appliedAmount, null, null, targetBefore, targetAfter);
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
        writeWalletBalanceMinor(request.sourcePlayerId(), walletAfter);
        writeBankBalanceMinor(request.sourcePlayerId(), bankAfter);
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
        Long walletAfter = addWithinMaximum(walletBefore, request.amountMinor(), maximumBalanceMinor);
        if (walletAfter == null) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The wallet balance would exceed the configured maximum", null, walletBefore);
        }
        long bankAfter = bankBefore - request.amountMinor();
        writeBankBalanceMinor(request.targetPlayerId(), bankAfter);
        writeWalletBalanceMinor(request.targetPlayerId(), walletAfter);
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), null, null,
                walletBefore, walletAfter);
        insertTransaction(record);
        return record;
    }

    private TransactionRecord persistFailure(WalletTransactionRequest request, TransactionFailureReason reason,
                                             String detail, Long sourceBalanceMinor, Long targetBalanceMinor) throws SQLException {
        TransactionRecord record = TransactionRecords.failed(request, reason, detail, sourceBalanceMinor, targetBalanceMinor);
        insertTransaction(record);
        return record;
    }

    private Optional<TransactionRecord> findExistingRequest(WalletTransactionRequest request) throws Exception {
        Optional<TransactionRecord> byId = findTransactionInternal(request.id());
        if (byId.isPresent()) {
            return byId;
        }
        return request.idempotencyKey() == null ? Optional.empty() : findTransactionByIdempotencyKey(request.idempotencyKey());
    }

    private Optional<TransactionRecord> findTransactionByIdempotencyKey(String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " WHERE idempotency_key=?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readTransaction(rows)) : Optional.empty();
            }
        }
    }

    private boolean hasSuccessfulReversal(UUID originalTransactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + TRANSACTIONS_TABLE
                + " WHERE successful_reversal_of_id=? LIMIT 1")) {
            statement.setString(1, originalTransactionId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private Long readWalletBalanceMinor(UUID playerId) throws Exception {
        return readAccountBalanceMinor(playerId, AccountBalanceColumn.WALLET);
    }

    private Long readBankBalanceMinor(UUID playerId) throws Exception {
        return readAccountBalanceMinor(playerId, AccountBalanceColumn.BANK);
    }

    private Long readAccountBalanceMinor(UUID playerId, AccountBalanceColumn column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + column.minorColumn + " FROM "
                + BALANCES_TABLE + " WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                long balance = rows.getLong(1);
                if (rows.wasNull() || balance < 0L) {
                    throw new SQLException("Account " + playerId + " contains an invalid " + column.minorColumn
                            + " balance");
                }
                return balance;
            }
        }
    }

    private void writeWalletBalanceMinor(UUID playerId, long amountMinor) throws SQLException {
        writeAccountBalanceMinor(playerId, AccountBalanceColumn.WALLET, amountMinor);
    }

    private void writeBankBalanceMinor(UUID playerId, long amountMinor) throws SQLException {
        writeAccountBalanceMinor(playerId, AccountBalanceColumn.BANK, amountMinor);
    }

    private void writeAccountBalanceMinor(UUID playerId, AccountBalanceColumn column, long amountMinor) throws SQLException {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("Account balances must not be negative");
        }
        String sql = "UPDATE " + BALANCES_TABLE + " SET " + column.legacyColumn + "=?, " + column.minorColumn
                + "=?, " + BALANCE_VERSION_COLUMN + "=" + BALANCE_VERSION_COLUMN + "+1 WHERE uuid=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDouble(1, Money.fromMinorUnits(amountMinor));
            statement.setLong(2, amountMinor);
            statement.setString(3, playerId.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Account " + playerId + " no longer exists");
            }
        }
    }

    private void writeOrCreateAccountBalanceMinor(UUID playerId, AccountBalanceColumn column, long amountMinor)
            throws SQLException {
        Long existingBalance = readAccountBalanceMinor(playerId, column);
        if (existingBalance == null) {
            insertAccountBalances(playerId, column == AccountBalanceColumn.WALLET ? amountMinor : 0L,
                    column == AccountBalanceColumn.BANK ? amountMinor : 0L);
            return;
        }
        writeAccountBalanceMinor(playerId, column, amountMinor);
    }

    private void insertAccountBalances(UUID playerId, long walletAmountMinor, long bankAmountMinor) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (walletAmountMinor < 0L || bankAmountMinor < 0L) {
            throw new IllegalArgumentException("Account balances must not be negative");
        }
        String sql = "INSERT INTO " + BALANCES_TABLE + "(uuid, balance, bank_balance, balance_minor, bank_balance_minor, "
                + BALANCE_VERSION_COLUMN + ") VALUES(?, ?, ?, ?, ?, 0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setDouble(2, Money.fromMinorUnits(walletAmountMinor));
            statement.setDouble(3, Money.fromMinorUnits(bankAmountMinor));
            statement.setLong(4, walletAmountMinor);
            statement.setLong(5, bankAmountMinor);
            statement.executeUpdate();
        }
    }

    private void validateWalletBankRequest(WalletTransactionRequest request, boolean mainToBank) {
        boolean valid = mainToBank
                ? request.operation() == WalletOperation.DEBIT && request.sourcePlayerId() != null
                && request.targetPlayerId() == null
                : request.operation() == WalletOperation.CREDIT && request.targetPlayerId() != null
                && request.sourcePlayerId() == null;
        if (!valid) {
            throw new IllegalArgumentException("The wallet-bank request operation does not match its direction");
        }
    }

    private Long addWithinMaximum(long current, long increment, long maximumBalanceMinor) {
        try {
            long result = Math.addExact(current, increment);
            return maximumBalanceMinor == 0 || result <= maximumBalanceMinor ? result : null;
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private long absoluteDifference(long first, long second) {
        try {
            return Math.abs(Math.subtractExact(first, second));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The wallet balance difference is out of range", exception);
        }
    }

    private void insertTransaction(TransactionRecord record) throws SQLException {
        String sql = "INSERT INTO " + TRANSACTIONS_TABLE + " (id, timestamp_epoch_millis, transaction_type, "
                + "transaction_status, origin_type, origin_player_id, origin_identifier, wallet_operation, "
                + "source_player_id, target_player_id, requested_amount_minor, applied_amount_minor, "
                + "source_balance_before_minor, source_balance_after_minor, target_balance_before_minor, "
                + "target_balance_after_minor, reason, metadata, reversal_of_transaction_id, successful_reversal_of_id, "
                + "batch_id, idempotency_key, failure_reason, failure_detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.id().toString());
            statement.setLong(2, record.timestamp().toEpochMilli());
            statement.setString(3, record.type().name());
            statement.setString(4, record.status().name());
            statement.setString(5, record.origin().type().name());
            setNullableUuid(statement, 6, record.origin().playerId());
            statement.setString(7, record.origin().identifier());
            statement.setString(8, record.operation().name());
            setNullableUuid(statement, 9, record.sourcePlayerId());
            setNullableUuid(statement, 10, record.targetPlayerId());
            statement.setLong(11, record.requestedAmountMinor());
            statement.setLong(12, record.appliedAmountMinor());
            setNullableLong(statement, 13, record.sourceBalanceBeforeMinor());
            setNullableLong(statement, 14, record.sourceBalanceAfterMinor());
            setNullableLong(statement, 15, record.targetBalanceBeforeMinor());
            setNullableLong(statement, 16, record.targetBalanceAfterMinor());
            statement.setString(17, record.reason());
            statement.setString(18, TransactionMetadataCodec.encode(record.metadata()));
            setNullableUuid(statement, 19, record.reversalOfTransactionId());
            setNullableUuid(statement, 20, record.isSuccessful() ? record.reversalOfTransactionId() : null);
            setNullableUuid(statement, 21, record.batchId());
            if (record.idempotencyKey() == null) {
                statement.setNull(22, Types.VARCHAR);
            } else {
                statement.setString(22, record.idempotencyKey());
            }
            statement.setString(23, record.failureReason().name());
            if (record.failureDetail() == null) {
                statement.setNull(24, Types.VARCHAR);
            } else {
                statement.setString(24, record.failureDetail());
            }
            statement.executeUpdate();
        }
    }

    private void setNullableUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
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
        List<Object> parameters = new ArrayList<>();
        appendTransactionQueryFilters(sql, parameters, query);
        sql.append(" ORDER BY timestamp_epoch_millis DESC, id DESC LIMIT ? OFFSET ?");
        parameters.add(query.limit() + 1);
        parameters.add(query.offset());

        List<TransactionRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParameters(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    records.add(readTransaction(rows));
                }
            }
        }

        boolean hasMore = records.size() > query.limit();
        if (hasMore) {
            records.remove(records.size() - 1);
        }
        return new TransactionPage(records, query.offset(), query.limit(), hasMore);
    }

    private void appendTransactionQueryFilters(StringBuilder sql, List<Object> parameters, TransactionQuery query) {
        if (query.transactionId() != null) {
            sql.append(" AND id=?");
            parameters.add(query.transactionId());
        }
        if (query.playerId() != null) {
            sql.append(" AND (source_player_id=? OR target_player_id=?)");
            parameters.add(query.playerId());
            parameters.add(query.playerId());
        }
        if (query.sourcePlayerId() != null) {
            sql.append(" AND source_player_id=?");
            parameters.add(query.sourcePlayerId());
        }
        if (query.targetPlayerId() != null) {
            sql.append(" AND target_player_id=?");
            parameters.add(query.targetPlayerId());
        }
        if (query.type() != null) {
            sql.append(" AND transaction_type=?");
            parameters.add(query.type());
        }
        if (query.status() != null) {
            sql.append(" AND transaction_status=?");
            parameters.add(query.status());
        }
        if (query.fromInclusive() != null) {
            sql.append(" AND timestamp_epoch_millis>=?");
            parameters.add(query.fromInclusive());
        }
        if (query.toExclusive() != null) {
            sql.append(" AND timestamp_epoch_millis<?");
            parameters.add(query.toExclusive());
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            int jdbcIndex = index + 1;
            if (value instanceof UUID uuid) {
                statement.setString(jdbcIndex, uuid.toString());
            } else if (value instanceof Enum<?> enumeration) {
                statement.setString(jdbcIndex, enumeration.name());
            } else if (value instanceof Instant instant) {
                statement.setLong(jdbcIndex, instant.toEpochMilli());
            } else if (value instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else {
                throw new SQLException("Unsupported SQLite query parameter type: " + value.getClass().getName());
            }
        }
    }

    @Override
    public synchronized EconomyStatistics getEconomyStatistics() throws Exception {
        return getEconomyAnalytics().statistics();
    }

    @Override
    public synchronized EconomyAnalytics getEconomyAnalytics() throws Exception {
        ensureConnected();
        WalletBalances balances = readWalletBalances();

        long totalTransactions = 0;
        long successfulTransactions = 0;
        long failedTransactions = 0;
        long cancelledTransactions = 0;
        long totalCredits = 0;
        long totalDebits = 0;
        long largestTransaction = 0;
        long transferVolume = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                totalTransactions = Math.addExact(totalTransactions, 1);
                TransactionRecord record = readTransaction(rows);
                switch (record.status()) {
                    case SUCCESS -> {
                        successfulTransactions = Math.addExact(successfulTransactions, 1);
                        long[] flows = TransactionRecords.moneySupplyFlows(record);
                        totalCredits = Math.addExact(totalCredits, flows[0]);
                        totalDebits = Math.addExact(totalDebits, flows[1]);
                        largestTransaction = Math.max(largestTransaction, record.appliedAmountMinor());
                        transferVolume = Math.addExact(transferVolume, TransactionRecords.transferVolume(record));
                    }
                    case FAILED -> failedTransactions = Math.addExact(failedTransactions, 1);
                    case CANCELLED -> cancelledTransactions = Math.addExact(cancelledTransactions, 1);
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
        long accountCount = 0;
        long totalMinor = 0;
        UUID richestPlayerId = null;
        long richestMinor = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid, " + WALLET_BALANCE_MINOR_COLUMN
                + " FROM " + BALANCES_TABLE);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                UUID playerId;
                try {
                    playerId = UUID.fromString(rows.getString("uuid"));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("Balances contains an invalid player UUID", exception);
                }
                long minor = rows.getLong(WALLET_BALANCE_MINOR_COLUMN);
                if (rows.wasNull() || minor < 0L) {
                    throw new SQLException("Balances contains an invalid wallet balance for " + playerId);
                }
                try {
                    accountCount = Math.addExact(accountCount, 1);
                    totalMinor = Math.addExact(totalMinor, minor);
                } catch (ArithmeticException exception) {
                    throw new SQLException("Wallet balance totals exceed the supported numeric range", exception);
                }
                if (richestPlayerId == null || minor > richestMinor
                        || (minor == richestMinor && playerId.toString().compareTo(richestPlayerId.toString()) < 0)) {
                    richestPlayerId = playerId;
                    richestMinor = minor;
                }
            }
        }
        return new WalletBalances(accountCount, totalMinor, richestPlayerId, richestMinor);
    }

    @Override
    public synchronized List<TransactionRecord> getAllWalletTransactions() throws Exception {
        ensureConnected();
        List<TransactionRecord> transactions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + TRANSACTION_COLUMNS + " FROM "
                + TRANSACTIONS_TABLE + " ORDER BY timestamp_epoch_millis ASC, id ASC");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                transactions.add(readTransaction(rows));
            }
        }
        return List.copyOf(transactions);
    }

    private PlayerIdentity readPlayerIdentity(ResultSet rows) throws SQLException {
        try {
            UUID playerId = UUID.fromString(rows.getString("uuid"));
            return new PlayerIdentity(playerId, rows.getString("last_known_name"),
                    rows.getString("normalized_name"), Instant.ofEpochMilli(rows.getLong("last_login_epoch_millis")),
                    readEnum(PlayerType.class, rows.getString("player_type"), "player type"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("Player identity registry contains an invalid record", exception);
        }
    }

    private void upsertPlayerIdentityInternal(PlayerIdentity identity) throws SQLException {
        String sql = "INSERT INTO " + PLAYER_IDENTITIES_TABLE + "(uuid, last_known_name, normalized_name, "
                + "last_login_epoch_millis, player_type) VALUES(?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET "
                + "last_known_name=CASE WHEN excluded.last_login_epoch_millis>=last_login_epoch_millis "
                + "THEN excluded.last_known_name ELSE last_known_name END, "
                + "normalized_name=CASE WHEN excluded.last_login_epoch_millis>=last_login_epoch_millis "
                + "THEN excluded.normalized_name ELSE normalized_name END, "
                + "last_login_epoch_millis=MAX(last_login_epoch_millis, excluded.last_login_epoch_millis), "
                + "player_type=CASE WHEN player_type='BEDROCK' OR excluded.player_type='BEDROCK' THEN 'BEDROCK' "
                + "WHEN excluded.player_type<>'UNKNOWN' THEN excluded.player_type ELSE player_type END";
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
            UUID id = UUID.fromString(rows.getString("id"));
            Instant timestamp = Instant.ofEpochMilli(rows.getLong("timestamp_epoch_millis"));
            TransactionType type = readEnum(TransactionType.class, rows.getString("transaction_type"), "transaction type");
            TransactionStatus status = readEnum(TransactionStatus.class, rows.getString("transaction_status"), "transaction status");
            TransactionOriginType originType = readEnum(TransactionOriginType.class, rows.getString("origin_type"), "origin type");
            TransactionOrigin origin = new TransactionOrigin(originType, readNullableUuid(rows, "origin_player_id"),
                    rows.getString("origin_identifier"));
            WalletOperation operation = readEnum(WalletOperation.class, rows.getString("wallet_operation"), "wallet operation");
            return new TransactionRecord(id, timestamp, type, status, origin, operation,
                    readNullableUuid(rows, "source_player_id"), readNullableUuid(rows, "target_player_id"),
                    rows.getLong("requested_amount_minor"), rows.getLong("applied_amount_minor"),
                    getNullableLong(rows, "source_balance_before_minor"), getNullableLong(rows, "source_balance_after_minor"),
                    getNullableLong(rows, "target_balance_before_minor"), getNullableLong(rows, "target_balance_after_minor"),
                    rows.getString("reason"), TransactionMetadataCodec.decode(rows.getString("metadata")),
                    readNullableUuid(rows, "reversal_of_transaction_id"), readNullableUuid(rows, "batch_id"),
                    rows.getString("idempotency_key"),
                    readEnum(TransactionFailureReason.class, rows.getString("failure_reason"), "failure reason"),
                    rows.getString("failure_detail"));
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Wallet journal contains an invalid transaction record", exception);
        }
    }

    private <T extends Enum<T>> T readEnum(Class<T> type, String value, String column) throws SQLException {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new SQLException("Wallet journal contains an invalid " + column, exception);
        }
    }

    private UUID readNullableUuid(ResultSet rows, String column) throws SQLException {
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

    private Long getNullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private Map<UUID, Long> toMinorUnits(Map<UUID, Double> balances) {
        Objects.requireNonNull(balances, "balances");
        Map<UUID, Long> minorBalances = new HashMap<>(balances.size());
        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("Account balances must not contain null keys or values");
            }
            long amountMinor = Money.toMinorUnits(entry.getValue());
            if (amountMinor < 0L) {
                throw new IllegalArgumentException("Account balances must not be negative");
            }
            minorBalances.put(entry.getKey(), amountMinor);
        }
        return Map.copyOf(minorBalances);
    }

    private <T> T inTransaction(SqlOperation<T> operation) throws Exception {
        ensureConnected();
        Connection activeConnection = connection;
        boolean autoCommit = activeConnection.getAutoCommit();
        boolean started = false;
        Exception failure = null;
        try {
            activeConnection.setAutoCommit(false);
            transactionActive = true;
            started = true;
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
                    if (!activeConnection.isClosed()) {
                        activeConnection.setAutoCommit(autoCommit);
                    }
                } catch (SQLException resetFailure) {
                    if (failure != null) {
                        failure.addSuppressed(resetFailure);
                    } else {
                        throw resetFailure;
                    }
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Closing is best effort; no caller can recover a connection that is already broken.
        } finally {
            connection = null;
            transactionActive = false;
        }
    }

    @Override
    public boolean isDebug() {
        return debug;
    }

    private enum AccountBalanceColumn {
        WALLET(WALLET_BALANCE_COLUMN, WALLET_BALANCE_MINOR_COLUMN),
        BANK(BANK_BALANCE_COLUMN, BANK_BALANCE_MINOR_COLUMN);

        private final String legacyColumn;
        private final String minorColumn;

        AccountBalanceColumn(String legacyColumn, String minorColumn) {
            this.legacyColumn = legacyColumn;
            this.minorColumn = minorColumn;
        }
    }

    private record LegacyBalance(UUID playerId, long amountMinor) {
    }

    private record WalletBalances(long accountCount, long totalMinor, UUID richestPlayerId, long richestMinor) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run() throws Exception;
    }
}
