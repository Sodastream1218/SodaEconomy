package de.sodaeconomy.storage;

import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionService;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises MySQL-specific schema, JDBC, transaction, and concurrency behaviour. */
@Tag("mysql-integration")
@ResourceLock("mysql-integration")
@EnabledIfEnvironmentVariable(named = "SODAECONOMY_TEST_MYSQL", matches = "true")
class MySQLStorageIntegrationTest extends MockBukkitTestBase {
    private final List<UUID> createdAccounts = new ArrayList<>();
    private MySqlTestEnvironment mysql;

    @org.junit.jupiter.api.BeforeEach
    void configureMySql() {
        mysql = MySqlTestEnvironment.load();
        mysql.applyTo(plugin);
    }

    @AfterEach
    void deleteTestRows() throws SQLException {
        if (mysql == null || createdAccounts.isEmpty()) return;
        try (Connection connection = mysql.openConnection();
             PreparedStatement deleteTransactions = connection.prepareStatement(
                     "DELETE FROM wallet_transactions WHERE source_player_id=? OR target_player_id=? OR origin_player_id=?");
             PreparedStatement deleteBalances = connection.prepareStatement("DELETE FROM balances WHERE uuid=?");
             PreparedStatement deleteIdentities = connection.prepareStatement("DELETE FROM player_identities WHERE uuid=?")) {
            for (UUID account : createdAccounts) {
                deleteTransactions.setString(1, account.toString());
                deleteTransactions.setString(2, account.toString());
                deleteTransactions.setString(3, account.toString());
                deleteTransactions.addBatch();
                deleteBalances.setString(1, account.toString());
                deleteBalances.addBatch();
                deleteIdentities.setString(1, account.toString());
                deleteIdentities.addBatch();
            }
            deleteTransactions.executeBatch();
            deleteBalances.executeBatch();
            deleteIdentities.executeBatch();
        }
    }

    @Test
    void migratesLegacyBalancesAndRecordsTheSchemaVersion() throws Exception {
        UUID legacyAccount = newAccountId();
        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS wallet_transactions");
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_runtime_state");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_scheduled_jobs");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_schema_version");
            statement.executeUpdate("DROP TABLE IF EXISTS balances");
            statement.executeUpdate("CREATE TABLE balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO balances(uuid, balance) VALUES(?, ?)")) {
                insert.setString(1, legacyAccount.toString());
                insert.setDouble(2, 37.5D);
                insert.executeUpdate();
            }
        }

        MySQLStorage storage = initializedStorage();
        try {
            assertEquals(37.5D, storage.getBalance(legacyAccount));
            assertEquals(0D, storage.getBankBalance(legacyAccount));
            assertTrue(columnExists("balances", "bank_balance"));
            assertTrue(columnExists("balances", "balance_minor"));
            assertTrue(columnExists("balances", "bank_balance_minor"));
            assertEquals(3_750L, minorValueInDatabase(legacyAccount, "balance_minor"));
            assertEquals(0L, minorValueInDatabase(legacyAccount, "bank_balance_minor"));
            assertTrue(tableExists("wallet_transactions"));
            assertTrue(tableExists("sodaeconomy_scheduled_jobs"));
            assertEquals(6, schemaVersion());
            assertEquals(1, walletTransactionsFor(legacyAccount));
        } finally {
            storage.close();
        }
    }

    @Test
    void resumesVersionTwoExactBalanceMigrationAndUsesMinorColumnsAtRuntime() throws Exception {
        UUID alreadyMigrated = newAccountId();
        UUID pendingMigration = newAccountId();
        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS wallet_transactions");
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_runtime_state");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_scheduled_jobs");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_schema_version");
            statement.executeUpdate("DROP TABLE IF EXISTS balances");
            statement.executeUpdate("CREATE TABLE balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL, "
                    + "bank_balance DOUBLE NOT NULL DEFAULT 0, balance_minor BIGINT NULL, bank_balance_minor BIGINT NULL)");
            statement.executeUpdate("CREATE TABLE sodaeconomy_schema_version (schema_name VARCHAR(64) PRIMARY KEY, version INT NOT NULL)");
            try (PreparedStatement version = connection.prepareStatement(
                    "INSERT INTO sodaeconomy_schema_version(schema_name, version) VALUES(?, ?)" );
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO balances(uuid, balance, bank_balance, "
                         + "balance_minor, bank_balance_minor) VALUES(?, ?, ?, ?, ?)")) {
                version.setString(1, "sodaeconomy");
                version.setInt(2, 2);
                version.executeUpdate();

                // Both minor values prove that a partially completed earlier run is resumed
                // without recalculating the already canonical account.
                insert.setString(1, alreadyMigrated.toString());
                insert.setDouble(2, 99.99D);
                insert.setDouble(3, 88.88D);
                insert.setLong(4, 123L);
                insert.setLong(5, 456L);
                insert.addBatch();

                insert.setString(1, pendingMigration.toString());
                insert.setDouble(2, 7.89D);
                insert.setDouble(3, 0.12D);
                insert.setNull(4, java.sql.Types.BIGINT);
                insert.setNull(5, java.sql.Types.BIGINT);
                insert.addBatch();
                insert.executeBatch();
            }
        }

        MySQLStorage storage = initializedStorage();
        try {
            assertEquals(6, schemaVersion());
            assertEquals(1.23D, storage.getBalance(alreadyMigrated));
            assertEquals(4.56D, storage.getBankBalance(alreadyMigrated));
            assertEquals(7.89D, storage.getBalance(pendingMigration));
            assertEquals(0.12D, storage.getBankBalance(pendingMigration));
            assertEquals(123L, minorValueInDatabase(alreadyMigrated, "balance_minor"));
            assertEquals(456L, minorValueInDatabase(alreadyMigrated, "bank_balance_minor"));
            assertEquals(789L, minorValueInDatabase(pendingMigration, "balance_minor"));
            assertEquals(12L, minorValueInDatabase(pendingMigration, "bank_balance_minor"));
            assertFalse(columnIsNullable("balances", "balance_minor"));
            assertFalse(columnIsNullable("balances", "bank_balance_minor"));

            // Legacy columns remain compatibility mirrors but can no longer alter runtime truth.
            assertEquals(1.23D, valueInDatabase(alreadyMigrated, "balance"));
            assertEquals(4.56D, valueInDatabase(alreadyMigrated, "bank_balance"));
        } finally {
            storage.close();
        }
    }

    @Test
    void reinitializesSchemaWithoutChangingExistingBalances() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage initial = initializedStorage();
        try {
            initial.getOrCreateBalance(playerId, 125D);
            initial.setBankBalance(playerId, 40D);
        } finally {
            initial.close();
        }

        MySQLStorage reinitialized = initializedStorage();
        try {
            assertEquals(125D, reinitialized.getBalance(playerId));
            assertEquals(40D, reinitialized.getBankBalance(playerId));
            assertEquals(6, schemaVersion());
            assertEquals(1, walletTransactionsFor(playerId));
        } finally {
            reinitialized.close();
        }
    }


    @Test
    void createsRuntimeStateGateOnFreshInstall() throws Exception {
        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS wallet_transactions");
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_runtime_state");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_scheduled_jobs");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_schema_version");
            statement.executeUpdate("DROP TABLE IF EXISTS balances");
        }

        MySQLStorage storage = initializedStorage();
        try {
            RuntimeState state = runtimeState();

            assertEquals(6, schemaVersion());
            assertTrue(tableExists("sodaeconomy_runtime_state"));
            assertTrue(tableExists("player_identities"));
            assertFalse(state.maintenanceActive());
            assertNull(state.ownerToken());
            assertNull(state.reason());
            assertEquals(0L, state.updatedEpochMillis());
        } finally {
            storage.close();
        }
    }

    @Test
    void migratesSchemaFourToRuntimeStateWithoutChangingExistingData() throws Exception {
        UUID playerId = newAccountId();
        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS wallet_transactions");
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_runtime_state");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_scheduled_jobs");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_schema_version");
            statement.executeUpdate("DROP TABLE IF EXISTS balances");
            statement.executeUpdate("CREATE TABLE balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL, "
                    + "bank_balance DOUBLE NOT NULL DEFAULT 0, balance_minor BIGINT NOT NULL, bank_balance_minor BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE sodaeconomy_scheduled_jobs (job_name VARCHAR(64) PRIMARY KEY, "
                    + "last_run_epoch_millis BIGINT NOT NULL, interval_millis BIGINT NOT NULL, "
                    + "configuration_signature VARCHAR(160) NOT NULL)");
            statement.executeUpdate("CREATE TABLE sodaeconomy_schema_version (schema_name VARCHAR(64) PRIMARY KEY, version INT NOT NULL)");
            try (PreparedStatement version = connection.prepareStatement(
                    "INSERT INTO sodaeconomy_schema_version(schema_name, version) VALUES(?, ?)");
                 PreparedStatement insertBalance = connection.prepareStatement("INSERT INTO balances(uuid, balance, bank_balance, "
                         + "balance_minor, bank_balance_minor) VALUES(?, ?, ?, ?, ?)");
                 PreparedStatement insertJob = connection.prepareStatement("INSERT INTO sodaeconomy_scheduled_jobs(job_name, "
                         + "last_run_epoch_millis, interval_millis, configuration_signature) VALUES(?, ?, ?, ?)")) {
                version.setString(1, "sodaeconomy");
                version.setInt(2, 4);
                version.executeUpdate();

                insertBalance.setString(1, playerId.toString());
                insertBalance.setDouble(2, 12.34D);
                insertBalance.setDouble(3, 5.67D);
                insertBalance.setLong(4, 1_234L);
                insertBalance.setLong(5, 567L);
                insertBalance.executeUpdate();

                insertJob.setString(1, "bank-interest");
                insertJob.setLong(2, 42L);
                insertJob.setLong(3, 3_600_000L);
                insertJob.setString(4, "0.05|0|3600000");
                insertJob.executeUpdate();
            }
        }

        MySQLStorage storage = initializedStorage();
        try {
            assertEquals(6, schemaVersion());
            assertTrue(tableExists("sodaeconomy_runtime_state"));
            assertTrue(tableExists("player_identities"));
            assertEquals(12.34D, storage.getBalance(playerId));
            assertEquals(5.67D, storage.getBankBalance(playerId));
            assertEquals(42L, scheduledJobLastRun("bank-interest"));
            assertFalse(runtimeState().maintenanceActive());
        } finally {
            storage.close();
            try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM sodaeconomy_scheduled_jobs WHERE job_name='bank-interest'");
            }
        }
    }

    @Test
    void migratesSchemaFiveToThePlayerIdentityRegistryWithoutChangingExistingData() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage initial = initializedStorage();
        try {
            initial.getOrCreateBalance(playerId, 64.25D);
            initial.setBankBalance(playerId, 8.75D);
        } finally {
            initial.close();
        }

        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("UPDATE sodaeconomy_schema_version SET version=5 WHERE schema_name='sodaeconomy'");
        }

        MySQLStorage migrated = initializedStorage();
        try {
            assertEquals(6, schemaVersion());
            assertTrue(tableExists("player_identities"));
            assertEquals(64.25D, migrated.getBalance(playerId));
            assertEquals(8.75D, migrated.getBankBalance(playerId));
            assertTrue(migrated.getAllPlayerIdentities().isEmpty());
        } finally {
            migrated.close();
        }
    }

    @Test
    void maintenanceLeaseUpdatesRuntimeStateAndCanBeReacquired() throws Exception {
        MySQLStorage storage = initializedStorage();
        try {
            StorageMaintenanceCoordinator.MaintenanceLease lease = storage.acquireMaintenanceLease("integration maintenance");
            RuntimeState activeState = runtimeState();

            assertTrue(activeState.maintenanceActive());
            assertNotNull(activeState.ownerToken());
            assertEquals("integration maintenance", activeState.reason());
            assertThrows(IllegalStateException.class, () -> storage.acquireMaintenanceLease("second local lease"));

            lease.close();
            RuntimeState releasedState = runtimeState();
            assertFalse(releasedState.maintenanceActive());
            assertNull(releasedState.ownerToken());
            assertNull(releasedState.reason());

            StorageMaintenanceCoordinator.MaintenanceLease reacquired = storage.acquireMaintenanceLease("second lease");
            reacquired.close();
            assertFalse(runtimeState().maintenanceActive());
        } finally {
            storage.close();
        }
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void rejectsConcurrentMaintenanceLeaseFromAnotherStorageInstance() throws Exception {
        MySQLStorage first = initializedStorage();
        MySQLStorage second = initializedStorage();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            StorageMaintenanceCoordinator.MaintenanceLease firstLease = first.acquireMaintenanceLease("first lease");
            Future<StorageMaintenanceCoordinator.MaintenanceLease> blockedLease = executor.submit(
                    () -> second.acquireMaintenanceLease("second lease"));

            ExecutionException failure = assertThrows(ExecutionException.class, blockedLease::get);
            assertInstanceOf(SQLException.class, failure.getCause());

            firstLease.close();
            StorageMaintenanceCoordinator.MaintenanceLease retry = second.acquireMaintenanceLease("retry lease");
            retry.close();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            first.close();
            second.close();
        }
    }

    @Test
    void createsReadsUpdatesAndDeletesBalancesThroughTheDatabase() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            assertEquals(100D, storage.getOrCreateBalance(playerId, 100D));
            storage.setBalance(playerId, 123.45D);
            storage.setBankBalance(playerId, 8.5D);

            assertEquals(123.45D, storage.getBalance(playerId));
            assertEquals(8.5D, storage.getBankBalance(playerId));
            assertEquals(12_345L, minorValueInDatabase(playerId, "balance_minor"));
            assertEquals(850L, minorValueInDatabase(playerId, "bank_balance_minor"));
            assertEquals(123.45D, valueInDatabase(playerId, "balance"));
            assertEquals(8.5D, valueInDatabase(playerId, "bank_balance"));

            deleteFromDatabase(playerId);
            assertNull(storage.getBalance(playerId));
            assertNull(storage.getBankBalance(playerId));
        } finally {
            storage.close();
        }
    }

    @Test
    void keepsTheFirstInitialBalanceForDuplicateAccountCreation() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            assertEquals(50D, storage.getOrCreateBalance(playerId, 50D));
            assertEquals(50D, storage.getOrCreateBalance(playerId, 500D));
            assertEquals(1, rowCount(playerId));
        } finally {
            storage.close();
        }
    }

    @Test
    void savesAndReadsBulkMainAndBankBalances() throws Exception {
        UUID firstPlayer = newAccountId();
        UUID secondPlayer = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.saveAll(Map.of(firstPlayer, 15D, secondPlayer, 30D));
            storage.saveAllBank(Map.of(firstPlayer, 5D, secondPlayer, 10D));

            assertEquals(Map.of(firstPlayer, 15D, secondPlayer, 30D), storage.getAllBalances()
                    .entrySet().stream()
                    .filter(entry -> entry.getKey().equals(firstPlayer) || entry.getKey().equals(secondPlayer))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            assertEquals(Map.of(firstPlayer, 5D, secondPlayer, 10D), storage.getAllBankBalances()
                    .entrySet().stream()
                    .filter(entry -> entry.getKey().equals(firstPlayer) || entry.getKey().equals(secondPlayer))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        } finally {
            storage.close();
        }
    }

    @Test
    void savesBulkBankBalancesUsingCanonicalMinorUnits() throws Exception {
        UUID firstPlayer = newAccountId();
        UUID secondPlayer = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.saveAllBankMinorUnits(Map.of(firstPlayer, 5_001L, secondPlayer, 9_999L));

            assertEquals(5_001L, storage.getAllBankBalanceMinorUnits().get(firstPlayer));
            assertEquals(9_999L, storage.getAllBankBalanceMinorUnits().get(secondPlayer));
            assertEquals(5_001L, minorValueInDatabase(firstPlayer, "bank_balance_minor"));
            assertEquals(9_999L, minorValueInDatabase(secondPlayer, "bank_balance_minor"));
            assertEquals(50.01D, valueInDatabase(firstPlayer, "bank_balance"));
            assertEquals(99.99D, valueInDatabase(secondPlayer, "bank_balance"));
        } finally {
            storage.close();
        }
    }

    @Test
    void commitsTransfersBetweenMainAndBankAccounts() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(playerId, 100D);

            assertTrue(storage.transferMainAndBank(playerId, true, 25D));
            assertTrue(storage.transferMainAndBank(playerId, false, 10D));

            assertEquals(85D, storage.getBalance(playerId));
            assertEquals(15D, storage.getBankBalance(playerId));
        } finally {
            storage.close();
        }
    }

    @Test
    void rollsBackTheDebitWhenTheCreditTargetDoesNotExist() throws Exception {
        UUID source = newAccountId();
        UUID missingTarget = UUID.randomUUID();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(source, 100D);

            assertThrows(SQLException.class, () -> storage.transferMain(source, missingTarget, 25D));

            assertEquals(100D, storage.getBalance(source));
            assertNull(storage.getBalance(missingTarget));
        } finally {
            storage.close();
        }
    }

    @Test
    void rejectsNonFiniteValuesWithoutPersistingThem() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(playerId, 10D);

            assertThrows(IllegalArgumentException.class, () -> storage.setBalance(playerId, Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> storage.setBankBalance(playerId, Double.NEGATIVE_INFINITY));

            assertEquals(10D, storage.getBalance(playerId));
            assertEquals(0D, storage.getBankBalance(playerId));
        } finally {
            storage.close();
        }
    }

    @Test
    void rejectsNullColumnsAndIgnoresMalformedLegacyIdentifiers() throws Exception {
        try (Connection connection = mysql.openConnection()) {
            try (PreparedStatement insertNull = connection.prepareStatement(
                    "INSERT INTO balances(uuid, balance, bank_balance, balance_minor, bank_balance_minor) VALUES(?, ?, ?, ?, ?)")) {
                insertNull.setString(1, UUID.randomUUID().toString());
                insertNull.setNull(2, java.sql.Types.DOUBLE);
                insertNull.setDouble(3, 0D);
                insertNull.setLong(4, 0L);
                insertNull.setLong(5, 0L);
                assertThrows(SQLException.class, insertNull::executeUpdate);
            }
            try (PreparedStatement insertMalformed = connection.prepareStatement(
                    "INSERT INTO balances(uuid, balance, bank_balance, balance_minor, bank_balance_minor) VALUES(?, ?, ?, ?, ?)")) {
                insertMalformed.setString(1, "not-a-uuid");
                insertMalformed.setDouble(2, 10D);
                insertMalformed.setDouble(3, 0D);
                insertMalformed.setLong(4, 1_000L);
                insertMalformed.setLong(5, 0L);
                insertMalformed.executeUpdate();
            }
        }

        MySQLStorage storage = initializedStorage();
        try {
            assertDoesNotThrow(storage::getAllBalances);
        } finally {
            storage.close();
            try (Connection connection = mysql.openConnection();
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM balances WHERE uuid=?")) {
                delete.setString(1, "not-a-uuid");
                delete.executeUpdate();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void serializesConcurrentTransfersWithoutLostUpdates() throws Exception {
        UUID source = newAccountId();
        UUID target = newAccountId();
        MySQLStorage storage = initializedStorage();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            storage.getOrCreateBalance(source, 100D);
            storage.getOrCreateBalance(target, 0D);
            List<Callable<Boolean>> operations = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                operations.add(() -> storage.transferMain(source, target, 1D));
            }

            List<Future<Boolean>> results = executor.invokeAll(operations);
            for (Future<Boolean> result : results) assertTrue(result.get());

            assertEquals(80D, storage.getBalance(source));
            assertEquals(20D, storage.getBalance(target));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            storage.close();
        }
    }


    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void preventsOverspendingAcrossTwoIndependentStorageInstances() throws Exception {
        UUID source = newAccountId();
        UUID target = newAccountId();
        MySQLStorage first = initializedStorage();
        MySQLStorage second = initializedStorage();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            first.getOrCreateBalance(source, 10D);
            first.getOrCreateBalance(target, 0D);

            List<Callable<TransactionRecord>> operations = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                MySQLStorage selected = index % 2 == 0 ? first : second;
                operations.add(() -> selected.executeWalletTransaction(transferRequest(source, target, 100L), 0L));
            }

            List<Future<TransactionRecord>> futures = executor.invokeAll(operations);
            int successful = 0;
            int rejected = 0;
            for (Future<TransactionRecord> future : futures) {
                TransactionRecord result = future.get();
                if (result.status() == TransactionStatus.SUCCESS) {
                    successful++;
                } else {
                    rejected++;
                    assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, result.failureReason());
                }
            }

            assertEquals(10, successful);
            assertEquals(10, rejected);
            assertEquals(0D, first.getBalance(source));
            assertEquals(10D, second.getBalance(target));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            first.close();
            second.close();
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void serializesParallelVaultStyleWithdrawalsAcrossTwoTransactionServices() throws Exception {
        UUID account = newAccountId();
        MySQLStorage firstStorage = initializedStorage();
        MySQLStorage secondStorage = initializedStorage();
        TransactionService first = new TransactionService(plugin, firstStorage, 10D, 0D);
        TransactionService second = new TransactionService(plugin, secondStorage, 10D, 0D);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            assertTrue(first.createAccountForIntegration(account, Duration.ofSeconds(3))
                    .completion().get(5, TimeUnit.SECONDS).created());

            List<Callable<TransactionResult>> operations = new ArrayList<>();
            Map<String, String> metadata = Map.of(
                    "integration", "vault",
                    "operation", "withdraw",
                    "vault_api", "1.7.1");
            for (int index = 0; index < 20; index++) {
                TransactionService selected = index % 2 == 0 ? first : second;
                operations.add(() -> selected.withdrawForIntegration(account, 1D,
                                TransactionOrigin.api("Vault"), "Vault withdrawal", metadata, Duration.ofSeconds(3))
                        .completion().get(5, TimeUnit.SECONDS));
            }

            List<Future<TransactionResult>> futures = executor.invokeAll(operations);
            int successful = 0;
            int rejected = 0;
            for (Future<TransactionResult> future : futures) {
                TransactionResult result = future.get();
                if (result.isSuccessful()) {
                    successful++;
                    assertEquals("vault", result.transaction().metadata().get("integration"));
                    assertEquals("withdraw", result.transaction().metadata().get("operation"));
                } else {
                    rejected++;
                    assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, result.failureReason());
                }
            }

            assertEquals(10, successful);
            assertEquals(10, rejected);
            assertEquals(0D, firstStorage.getBalance(account));
            long successfulVaultWithdrawals = firstStorage.getAllWalletTransactions().stream()
                    .filter(TransactionRecord::isSuccessful)
                    .filter(record -> "vault".equals(record.metadata().get("integration")))
                    .filter(record -> "withdraw".equals(record.metadata().get("operation")))
                    .count();
            assertEquals(10L, successfulVaultWithdrawals);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            first.close();
            second.close();
            firstStorage.close();
            secondStorage.close();
        }
    }


    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void executesTheSameIdempotentRequestOnlyOnceAcrossTwoInstances() throws Exception {
        UUID source = newAccountId();
        UUID target = newAccountId();
        MySQLStorage first = initializedStorage();
        MySQLStorage second = initializedStorage();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            first.getOrCreateBalance(source, 100D);
            first.getOrCreateBalance(target, 0D);
            WalletTransactionRequest request = transferRequest(source, target, 2_500L,
                    "network-idempotency-" + UUID.randomUUID());

            Future<TransactionRecord> firstResult = executor.submit(
                    () -> first.executeWalletTransaction(request, 0L));
            Future<TransactionRecord> secondResult = executor.submit(
                    () -> second.executeWalletTransaction(request, 0L));

            TransactionRecord a = firstResult.get();
            TransactionRecord b = secondResult.get();
            assertEquals(TransactionStatus.SUCCESS, a.status());
            assertEquals(a.id(), b.id());
            assertEquals(75D, first.getBalance(source));
            assertEquals(25D, second.getBalance(target));
            assertEquals(1, transactionRowCount(request.id()));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            first.close();
            second.close();
        }
    }

    @Test
    void commitsWalletBalancesAndJournalRecordsAtomically() throws Exception {
        UUID source = newAccountId();
        UUID target = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(source, 100D);
            storage.getOrCreateBalance(target, 10D);
            TransactionRecord result = storage.executeWalletTransaction(transferRequest(source, target, 2_500L), 0L);

            assertEquals(TransactionStatus.SUCCESS, result.status());
            assertEquals(75D, storage.getBalance(source));
            assertEquals(35D, storage.getBalance(target));
            assertEquals(result.id(), storage.findTransaction(result.id()).orElseThrow().id());
            assertEquals(1, transactionRowCount(result.id()));
        } finally {
            storage.close();
        }
    }

    @Test
    void recordsFailedWalletTransactionsWithoutChangingBalancesOrStatisticsSources() throws Exception {
        UUID source = newAccountId();
        UUID target = newAccountId();
        UUID missingTarget = UUID.randomUUID();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(source, 100D);
            storage.getOrCreateBalance(target, 10D);
            var statisticsBeforeTransfer = storage.getEconomyStatistics();

            TransactionRecord transfer = storage.executeWalletTransaction(transferRequest(source, target, 2_500L), 0L);
            TransactionRecord failed = storage.executeWalletTransaction(transferRequest(source, missingTarget, 500L), 0L);
            var statisticsAfter = storage.getEconomyStatistics();

            assertEquals(TransactionStatus.SUCCESS, transfer.status());
            assertEquals(TransactionStatus.FAILED, failed.status());
            assertEquals(TransactionFailureReason.ACCOUNT_NOT_FOUND, failed.failureReason());
            assertEquals(75D, storage.getBalance(source));
            assertEquals(35D, storage.getBalance(target));
            assertEquals(3, walletTransactionsFor(source));
            assertEquals(statisticsBeforeTransfer.totalCreditsMinor(), statisticsAfter.totalCreditsMinor());
            assertEquals(statisticsBeforeTransfer.totalDebitsMinor(), statisticsAfter.totalDebitsMinor());
            assertEquals(statisticsBeforeTransfer.totalTransactionCount() + 2L,
                    statisticsAfter.totalTransactionCount());
            assertEquals(statisticsBeforeTransfer.failedTransactionCount() + 1L,
                    statisticsAfter.failedTransactionCount());
        } finally {
            storage.close();
        }
    }

    @Test
    void commitsWalletBankBalancesAndTheirWalletJournalRecordsAtomically() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.getOrCreateBalance(playerId, 100D);

            TransactionRecord deposit = storage.executeWalletBankTransaction(
                    walletToBankRequest(playerId, 2_500L), true, 0L);
            TransactionRecord withdrawal = storage.executeWalletBankTransaction(
                    bankToWalletRequest(playerId, 1_000L), false, 0L);
            TransactionRecord rejectedWithdrawal = storage.executeWalletBankTransaction(
                    bankToWalletRequest(playerId, 2_000L), false, 0L);

            assertEquals(TransactionStatus.SUCCESS, deposit.status());
            assertEquals(TransactionStatus.SUCCESS, withdrawal.status());
            assertEquals(TransactionStatus.FAILED, rejectedWithdrawal.status());
            assertEquals(TransactionFailureReason.INSUFFICIENT_FUNDS, rejectedWithdrawal.failureReason());
            assertEquals(85D, storage.getBalance(playerId));
            assertEquals(15D, storage.getBankBalance(playerId));
            assertEquals(4, walletTransactionsFor(playerId));
        } finally {
            storage.close();
        }
    }

    @Test
    void coordinatesBankInterestAcrossTwoStorageInstances() throws Exception {
        UUID playerId = newAccountId();
        try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM sodaeconomy_scheduled_jobs WHERE job_name='bank-interest'");
        }

        MySQLStorage first = initializedStorage();
        MySQLStorage second = initializedStorage();
        try {
            first.getOrCreateBalance(playerId, 0D);
            first.setBankBalance(playerId, 100D);
            Instant firstRun = Instant.parse("2026-08-01T00:00:00Z");
            Duration interval = Duration.ofHours(1);

            BankInterestResult firstResult = first.applyBankInterest(BigDecimal.valueOf(0.10D), 0L,
                    interval, firstRun);
            BankInterestResult duplicateResult = second.applyBankInterest(BigDecimal.valueOf(0.10D), 0L,
                    interval, firstRun.plusSeconds(30));

            assertTrue(firstResult.executed());
            assertEquals(1, firstResult.changedAccounts());
            assertFalse(duplicateResult.executed());
            assertEquals(110D, first.getBankBalance(playerId));

            BankInterestResult nextResult = second.applyBankInterest(BigDecimal.valueOf(0.10D), 0L,
                    interval, firstRun.plus(interval));
            assertTrue(nextResult.executed());
            assertEquals(121D, second.getBankBalance(playerId));

            assertThrows(SQLException.class, () -> first.applyBankInterest(BigDecimal.valueOf(0.05D), 0L,
                    interval, firstRun.plus(interval.multipliedBy(2L))));
            assertEquals(121D, first.getBankBalance(playerId));
        } finally {
            first.close();
            second.close();
            try (Connection connection = mysql.openConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM sodaeconomy_scheduled_jobs WHERE job_name='bank-interest'");
            }
        }
    }

    @Test
    void importsLegacySnapshotBalancesWithBaselineJournalEntriesInTheSameTransaction() throws Exception {
        UUID playerId = newAccountId();
        MySQLStorage storage = initializedStorage();
        try {
            storage.replaceSnapshot(new StorageSnapshot(Map.of(playerId, 125D), Map.of(playerId, 25D)));

            assertEquals(125D, storage.getBalance(playerId));
            assertEquals(25D, storage.getBankBalance(playerId));
            assertEquals(1, walletTransactionsFor(playerId));
            assertEquals(TransactionType.LEGACY_OPENING_BALANCE,
                    storage.getAllWalletTransactions().get(0).type());
        } finally {
            storage.close();
        }
    }

    @Test
    void importsExactMinorUnitSnapshotsWithoutRoundTrippingThroughDouble() throws Exception {
        UUID playerId = newAccountId();
        long walletMinor = 9_007_199_254_740_991L;
        long bankMinor = 9_007_199_254_740_123L;
        MySQLStorage storage = initializedStorage();
        try {
            storage.replaceMinorUnitSnapshot(Map.of(playerId, walletMinor), Map.of(playerId, bankMinor), List.of());

            assertEquals(walletMinor, storage.getAllBalanceMinorUnits().get(playerId));
            assertEquals(bankMinor, storage.getAllBankBalanceMinorUnits().get(playerId));
            assertEquals(walletMinor, minorValueInDatabase(playerId, "balance_minor"));
            assertEquals(bankMinor, minorValueInDatabase(playerId, "bank_balance_minor"));
        } finally {
            storage.close();
        }
    }

    private MySQLStorage initializedStorage() throws Exception {
        MySQLStorage storage = new MySQLStorage();
        storage.init(plugin);
        return storage;
    }

    private UUID newAccountId() {
        UUID playerId = UUID.randomUUID();
        createdAccounts.add(playerId);
        return playerId;
    }

    private boolean columnExists(String table, String column) throws SQLException {
        String query = "SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name=? AND column_name=?";
        try (Connection connection = mysql.openConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean tableExists(String table) throws SQLException {
        String query = "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name=?";
        try (Connection connection = mysql.openConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }


    private RuntimeState runtimeState() throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT maintenance_active, owner_token, reason, "
                     + "updated_epoch_millis FROM sodaeconomy_runtime_state WHERE state_name=?")) {
            statement.setString(1, "wallet-mutations");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return new RuntimeState(result.getBoolean("maintenance_active"), result.getString("owner_token"),
                        result.getString("reason"), result.getLong("updated_epoch_millis"));
            }
        }
    }

    private long scheduledJobLastRun(String jobName) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT last_run_epoch_millis FROM "
                     + "sodaeconomy_scheduled_jobs WHERE job_name=?")) {
            statement.setString(1, jobName);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private record RuntimeState(boolean maintenanceActive, String ownerToken, String reason, long updatedEpochMillis) { }

    private int schemaVersion() throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT version FROM sodaeconomy_schema_version WHERE schema_name=?")) {
            statement.setString(1, "sodaeconomy");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private double valueInDatabase(UUID playerId, String column) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM balances WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getDouble(1);
            }
        }
    }

    private long minorValueInDatabase(UUID playerId, String column) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM balances WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private boolean columnIsNullable(String table, String column) throws SQLException {
        String query = "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name=? AND column_name=?";
        try (Connection connection = mysql.openConnection(); PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return "YES".equalsIgnoreCase(result.getString(1));
            }
        }
    }

    private int rowCount(UUID playerId) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM balances WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private int walletTransactionsFor(UUID playerId) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM wallet_transactions WHERE source_player_id=? OR target_player_id=?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private int transactionRowCount(UUID transactionId) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM wallet_transactions WHERE id=?")) {
            statement.setString(1, transactionId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private WalletTransactionRequest transferRequest(UUID source, UUID target, long amountMinor) {
        return transferRequest(source, target, amountMinor, null);
    }

    private WalletTransactionRequest transferRequest(UUID source, UUID target, long amountMinor,
                                                     String idempotencyKey) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                TransactionType.PLAYER_TRANSFER, TransactionOrigin.system("MySQL integration test"),
                WalletOperation.TRANSFER, source, target, amountMinor, null,
                "MySQL transaction integration test", Map.of("test", "mysql"), null, null, idempotencyKey);
    }

    private WalletTransactionRequest walletToBankRequest(UUID playerId, long amountMinor) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                TransactionType.WALLET_TO_BANK, TransactionOrigin.system("MySQL integration test"),
                WalletOperation.DEBIT, playerId, null, amountMinor, null,
                "MySQL wallet-to-bank integration test", Map.of("test", "mysql"), null, null, null);
    }

    private WalletTransactionRequest bankToWalletRequest(UUID playerId, long amountMinor) {
        return new WalletTransactionRequest(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MILLIS),
                TransactionType.BANK_TO_WALLET, TransactionOrigin.system("MySQL integration test"),
                WalletOperation.CREDIT, null, playerId, amountMinor, null,
                "MySQL bank-to-wallet integration test", Map.of("test", "mysql"), null, null, null);
    }

    private void deleteFromDatabase(UUID playerId) throws SQLException {
        try (Connection connection = mysql.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM balances WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }
}
