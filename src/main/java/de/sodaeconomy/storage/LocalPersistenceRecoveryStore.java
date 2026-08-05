package de.sodaeconomy.storage;

import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionOriginType;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Crash-recovery anchor for the single-server YAML/SQLite write-behind cache.
 *
 * <p>The asynchronous decorator exposes accepted transactions before the backing storage has
 * necessarily committed them. To make that contract survive a hard JVM or host crash, this class
 * synchronously records the exact post-acceptance snapshot of wallets, bank balances, and wallet
 * journal entries before the public call returns. On the next startup the snapshot is atomically
 * installed into the backing local storage and then removed.</p>
 *
 * <p>MySQL never uses this component because it is already database-authoritative and shared across
 * server instances.</p>
 */
final class LocalPersistenceRecoveryStore {
    private static final int FORMAT_VERSION = 1;
    private static final String FILE_NAME = "local-persistence-recovery.yml";
    private static final String TMP_FILE_NAME = FILE_NAME + ".tmp";
    private static final String FORMAT_VERSION_PATH = "format_version";
    private static final String SAVED_AT_PATH = "saved_epoch_millis";
    private static final String BALANCES_SECTION = "balances";
    private static final String BANK_BALANCES_SECTION = "bank_balances";
    private static final String WALLET_TRANSACTIONS_SECTION = "wallet_transactions";

    private static final Comparator<TransactionRecord> TRANSACTION_ORDER =
            Comparator.comparing(TransactionRecord::timestamp).thenComparing(TransactionRecord::id);

    private final JavaPlugin plugin;
    private final File file;
    private final File temporaryFile;

    LocalPersistenceRecoveryStore(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        this.temporaryFile = new File(plugin.getDataFolder(), TMP_FILE_NAME);
    }

    void restoreIfPresent(Storage storage) throws Exception {
        Objects.requireNonNull(storage, "storage");
        if (!file.isFile()) {
            cleanupTemporaryFile();
            return;
        }

        ExactStorageSnapshot snapshot = readSnapshot();
        plugin.getLogger().warning("[Persistence] Local recovery snapshot found. Restoring "
                + snapshot.balances().size() + " wallet balance(s), "
                + snapshot.bankBalances().size() + " bank balance(s), and "
                + snapshot.walletTransactions().size() + " wallet transaction(s) before enabling the async queue.");
        storage.replaceMinorUnitSnapshot(snapshot.balances(), snapshot.bankBalances(), snapshot.walletTransactions());
        deleteRecoveryFile();
        cleanupTemporaryFile();
        plugin.getLogger().info("[Persistence] Local recovery snapshot restored and cleared.");
    }

    void persistSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                         List<TransactionRecord> transactions) throws IOException {
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(balances, bankBalances, transactions);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set(FORMAT_VERSION_PATH, FORMAT_VERSION);
        cfg.set(SAVED_AT_PATH, System.currentTimeMillis());
        writeBalances(cfg, BALANCES_SECTION, snapshot.balances());
        writeBalances(cfg, BANK_BALANCES_SECTION, snapshot.bankBalances());
        writeTransactions(cfg, snapshot.walletTransactions());
        ensureParentDirectory();
        cfg.save(temporaryFile);
        moveAtomically(temporaryFile.toPath(), file.toPath());
    }

    void clear() {
        try {
            deleteRecoveryFile();
            cleanupTemporaryFile();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[Persistence] Could not delete the local recovery snapshot after the queue drained.", exception);
        }
    }

    private ExactStorageSnapshot readSnapshot() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        int version = cfg.getInt(FORMAT_VERSION_PATH, 0);
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported local persistence recovery format " + version);
        }
        return new ExactStorageSnapshot(readBalances(cfg, BALANCES_SECTION),
                readBalances(cfg, BANK_BALANCES_SECTION), readTransactions(cfg));
    }

    private void ensureParentDirectory() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            Files.createDirectories(parent.toPath());
        }
    }

    private void deleteRecoveryFile() throws IOException {
        Files.deleteIfExists(file.toPath());
    }

    private void cleanupTemporaryFile() throws IOException {
        Files.deleteIfExists(temporaryFile.toPath());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeBalances(YamlConfiguration cfg, String section, Map<UUID, Long> balances) {
        cfg.set(section, null);
        balances.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> cfg.set(section + "." + entry.getKey(), entry.getValue()));
    }

    private static Map<UUID, Long> readBalances(YamlConfiguration cfg, String section) {
        ConfigurationSection values = cfg.getConfigurationSection(section);
        if (values == null) {
            return Map.of();
        }
        Map<UUID, Long> balances = new LinkedHashMap<>();
        for (String key : values.getKeys(false)) {
            UUID playerId = UUID.fromString(key);
            Object raw = values.get(key);
            if (!(raw instanceof Number number)) {
                throw new IllegalStateException("Invalid recovery balance for " + playerId);
            }
            long amount = number.longValue();
            if (amount < 0L) {
                throw new IllegalStateException("Negative recovery balance for " + playerId);
            }
            balances.put(playerId, amount);
        }
        return balances;
    }

    private static void writeTransactions(YamlConfiguration cfg, List<TransactionRecord> transactions) {
        cfg.set(WALLET_TRANSACTIONS_SECTION, null);
        transactions.stream().sorted(TRANSACTION_ORDER).forEach(record -> {
            String base = WALLET_TRANSACTIONS_SECTION + "." + record.id();
            cfg.set(base + ".timestamp", record.timestamp().toString());
            cfg.set(base + ".type", record.type().name());
            cfg.set(base + ".status", record.status().name());
            cfg.set(base + ".origin.type", record.origin().type().name());
            cfg.set(base + ".origin.player_id", record.origin().playerId() == null
                    ? null : record.origin().playerId().toString());
            cfg.set(base + ".origin.identifier", record.origin().identifier());
            cfg.set(base + ".operation", record.operation().name());
            cfg.set(base + ".source_player_id", record.sourcePlayerId() == null ? null : record.sourcePlayerId().toString());
            cfg.set(base + ".target_player_id", record.targetPlayerId() == null ? null : record.targetPlayerId().toString());
            cfg.set(base + ".requested_amount_minor", record.requestedAmountMinor());
            cfg.set(base + ".applied_amount_minor", record.appliedAmountMinor());
            writeOptionalLong(cfg, base + ".source_balance_before_minor", record.sourceBalanceBeforeMinor());
            writeOptionalLong(cfg, base + ".source_balance_after_minor", record.sourceBalanceAfterMinor());
            writeOptionalLong(cfg, base + ".target_balance_before_minor", record.targetBalanceBeforeMinor());
            writeOptionalLong(cfg, base + ".target_balance_after_minor", record.targetBalanceAfterMinor());
            cfg.set(base + ".reason", record.reason());
            cfg.set(base + ".metadata", serializeMetadata(record.metadata()));
            cfg.set(base + ".reversal_of_transaction_id", record.reversalOfTransactionId() == null
                    ? null : record.reversalOfTransactionId().toString());
            cfg.set(base + ".batch_id", record.batchId() == null ? null : record.batchId().toString());
            cfg.set(base + ".idempotency_key", record.idempotencyKey());
            cfg.set(base + ".failure_reason", record.failureReason().name());
            cfg.set(base + ".failure_detail", record.failureDetail());
        });
    }

    private static void writeOptionalLong(YamlConfiguration cfg, String path, Long value) {
        cfg.set(path, value);
    }

    private static List<TransactionRecord> readTransactions(YamlConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection(WALLET_TRANSACTIONS_SECTION);
        if (section == null) {
            return List.of();
        }
        List<TransactionRecord> records = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String base = WALLET_TRANSACTIONS_SECTION + "." + key;
            UUID transactionId = UUID.fromString(key);
            Instant timestamp = Instant.parse(requireString(cfg, base + ".timestamp"));
            TransactionType type = TransactionType.valueOf(requireString(cfg, base + ".type"));
            TransactionStatus status = TransactionStatus.valueOf(requireString(cfg, base + ".status"));
            TransactionOrigin origin = readOrigin(cfg, base + ".origin");
            WalletOperation operation = WalletOperation.valueOf(requireString(cfg, base + ".operation"));
            UUID sourcePlayerId = readOptionalUuid(cfg, base + ".source_player_id");
            UUID targetPlayerId = readOptionalUuid(cfg, base + ".target_player_id");
            long requestedAmount = requireLong(cfg, base + ".requested_amount_minor");
            long appliedAmount = requireLong(cfg, base + ".applied_amount_minor");
            Long sourceBefore = optionalLong(cfg, base + ".source_balance_before_minor");
            Long sourceAfter = optionalLong(cfg, base + ".source_balance_after_minor");
            Long targetBefore = optionalLong(cfg, base + ".target_balance_before_minor");
            Long targetAfter = optionalLong(cfg, base + ".target_balance_after_minor");
            String reason = cfg.getString(base + ".reason");
            Map<String, String> metadata = readMetadata(cfg, base + ".metadata");
            UUID reversal = readOptionalUuid(cfg, base + ".reversal_of_transaction_id");
            UUID batchId = readOptionalUuid(cfg, base + ".batch_id");
            String idempotencyKey = cfg.getString(base + ".idempotency_key");
            TransactionFailureReason failureReason = TransactionFailureReason.valueOf(
                    cfg.getString(base + ".failure_reason", TransactionFailureReason.NONE.name()));
            String failureDetail = cfg.getString(base + ".failure_detail");
            records.add(new TransactionRecord(transactionId, timestamp, type, status, origin, operation,
                    sourcePlayerId, targetPlayerId, requestedAmount, appliedAmount, sourceBefore, sourceAfter,
                    targetBefore, targetAfter, reason, metadata, reversal, batchId, idempotencyKey,
                    failureReason, failureDetail));
        }
        records.sort(TRANSACTION_ORDER);
        return records;
    }

    private static TransactionOrigin readOrigin(YamlConfiguration cfg, String path) {
        TransactionOriginType type = TransactionOriginType.valueOf(requireString(cfg, path + ".type"));
        UUID playerId = readOptionalUuid(cfg, path + ".player_id");
        String identifier = requireString(cfg, path + ".identifier");
        return new TransactionOrigin(type, playerId, identifier);
    }

    private static String requireString(YamlConfiguration cfg, String path) {
        String value = cfg.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing recovery value: " + path);
        }
        return value;
    }

    private static long requireLong(YamlConfiguration cfg, String path) {
        Long value = optionalLong(cfg, path);
        if (value == null) {
            throw new IllegalStateException("Missing recovery value: " + path);
        }
        return value;
    }

    private static Long optionalLong(YamlConfiguration cfg, String path) {
        if (!cfg.contains(path)) {
            return null;
        }
        Object raw = cfg.get(path);
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException("Invalid recovery integer: " + path);
        }
        return number.longValue();
    }

    private static UUID readOptionalUuid(YamlConfiguration cfg, String path) {
        String value = cfg.getString(path);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static List<Map<String, String>> serializeMetadata(Map<String, String> metadata) {
        List<Map<String, String>> serialized = new ArrayList<>(metadata.size());
        metadata.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("key", entry.getKey());
            value.put("value", entry.getValue());
            serialized.add(value);
        });
        return serialized;
    }

    private static Map<String, String> readMetadata(YamlConfiguration cfg, String path) {
        Object raw = cfg.get(path);
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalStateException("Invalid recovery metadata: " + path);
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map) || map.get("key") == null || map.get("value") == null) {
                throw new IllegalStateException("Invalid recovery metadata entry: " + path);
            }
            metadata.put(String.valueOf(map.get("key")), String.valueOf(map.get("value")));
        }
        return metadata;
    }
}
