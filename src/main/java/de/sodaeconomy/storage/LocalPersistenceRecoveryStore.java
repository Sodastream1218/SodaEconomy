package de.sodaeconomy.storage;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionOriginType;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletOperation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.CRC32;

/**
 * Durable bridge for writes accepted by the local YAML/SQLite asynchronous queue.
 *
 * <p>The current format is an append-only, checksummed WAL. Each accepted queue write contributes
 * one record containing only the absolute post-write state of accounts touched by that write plus
 * its immutable wallet journal record when applicable. Committed entries are removed from the
 * in-memory pending set and the WAL is deleted when the queue drains or periodically compacted to
 * the still-pending set. Long-term transaction history therefore stays in the authoritative local
 * backend instead of being copied into recovery storage for every mutation.</p>
 *
 * <p>Recovery intentionally applies absolute state instead of re-executing business operations.
 * A backend commit followed by a crash before local cleanup is therefore safe: replaying the same
 * final balance and immutable transaction ID is idempotent from the user's perspective.</p>
 *
 * <p>The previous YAML snapshot format is retained as an upgrade-only reader. MySQL/MariaDB never
 * uses this component because that backend is already authoritative and shared across nodes.</p>
 */
class LocalPersistenceRecoveryStore {
    private static final int WAL_MAGIC = 0x534F4441; // "SODA"
    private static final int WAL_FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    private static final long MAX_WAL_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_COLLECTION_ENTRIES = 1_000_000;
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MIN_COMPACTION_COMMITS = 256;

    private static final String WAL_FILE_NAME = "local-persistence-recovery.wal";
    private static final String WAL_TMP_FILE_NAME = WAL_FILE_NAME + ".tmp";
    private static final String LEGACY_FILE_NAME = "local-persistence-recovery.yml";
    private static final String LEGACY_TMP_FILE_NAME = LEGACY_FILE_NAME + ".tmp";

    private static final String LEGACY_FORMAT_VERSION_PATH = "format_version";
    private static final String LEGACY_BALANCES_SECTION = "balances";
    private static final String LEGACY_BANK_BALANCES_SECTION = "bank_balances";
    private static final String LEGACY_WALLET_TRANSACTIONS_SECTION = "wallet_transactions";

    private static final Comparator<TransactionRecord> TRANSACTION_ORDER =
            Comparator.comparing(TransactionRecord::timestamp).thenComparing(TransactionRecord::id);

    private final JavaPlugin plugin;
    private final Path walFile;
    private final Path temporaryWalFile;
    private final File legacyFile;
    private final File legacyTemporaryFile;
    private final LinkedHashMap<UUID, LocalRecoveryState> pendingEntries = new LinkedHashMap<>();
    private int committedSinceCompaction;
    private boolean cleanupRequired;
    private boolean walIntegrityUncertain;

    LocalPersistenceRecoveryStore(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.walFile = new File(plugin.getDataFolder(), WAL_FILE_NAME).toPath();
        this.temporaryWalFile = new File(plugin.getDataFolder(), WAL_TMP_FILE_NAME).toPath();
        this.legacyFile = new File(plugin.getDataFolder(), LEGACY_FILE_NAME);
        this.legacyTemporaryFile = new File(plugin.getDataFolder(), LEGACY_TMP_FILE_NAME);
    }

    synchronized void restoreIfPresent(Storage storage) throws Exception {
        Objects.requireNonNull(storage, "storage");
        if (Files.isRegularFile(walFile) && legacyFile.isFile()) {
            throw new IllegalStateException("Both current and legacy local recovery files exist; refusing to guess which state is authoritative");
        }

        if (Files.isRegularFile(walFile)) {
            List<LocalRecoveryState> entries = readWal();
            if (entries.isEmpty()) {
                clearWalFiles();
                return;
            }
            plugin.getLogger().warning("[Persistence] Local recovery WAL found with " + entries.size()
                    + " unresolved/reconciliation record(s). Reconciling before enabling the async queue.");
            reconcileWalEntries(storage, entries);
            clearWalFiles();
            plugin.getLogger().info("[Persistence] Local recovery reconciliation completed and the WAL was cleared.");
            return;
        }

        cleanupTemporaryWal();
        if (legacyFile.isFile()) {
            restoreLegacySnapshot(storage);
        } else {
            cleanupLegacyTemporaryFile();
        }
    }

    /**
     * Establishes the durability barrier for one newly accepted queue write.
     *
     * <p>The record is forced to stable storage before this method returns. If it throws, the
     * caller must roll back its in-memory acceptance and must not report success.</p>
     */
    synchronized void appendPending(LocalRecoveryState state) throws IOException {
        Objects.requireNonNull(state, "state");
        if (pendingEntries.containsKey(state.writeId())) {
            throw new IllegalStateException("Duplicate local recovery write ID " + state.writeId());
        }
        if (walIntegrityUncertain) {
            throw new IOException("Local recovery WAL integrity is uncertain after an earlier write failure; "
                    + "new mutations are blocked until recovery state is safely cleared or the plugin restarts");
        }
        ensureParentDirectory();
        repairCommittedResidueBeforeAppend();
        byte[] payload = encodeState(state);
        appendFrame(payload);
        pendingEntries.put(state.writeId(), state);
    }

    /**
     * Marks one backend-confirmed write as no longer required for runtime recovery.
     *
     * <p>No acknowledgement record is required on disk. If the process crashes before cleanup,
     * the older absolute recovery record can be reconciled again without applying a transfer or
     * mutation twice. Cleanup failures therefore remain warnings instead of turning a confirmed
     * backend commit into an ambiguous retry.</p>
     */
    synchronized void markCommitted(UUID writeId) {
        if (writeId == null || pendingEntries.remove(writeId) == null) {
            return;
        }
        committedSinceCompaction++;
        try {
            if (pendingEntries.isEmpty()) {
                clearWalFiles();
                cleanupRequired = false;
                walIntegrityUncertain = false;
                committedSinceCompaction = 0;
                return;
            }
            int compactionThreshold = Math.max(MIN_COMPACTION_COMMITS, pendingEntries.size());
            if (committedSinceCompaction >= compactionThreshold) {
                compactPendingEntries();
                cleanupRequired = false;
                committedSinceCompaction = 0;
            }
        } catch (IOException exception) {
            cleanupRequired = true;
            // The old WAL still contains the committed entry. That is safe because recovery uses
            // absolute state and immutable transaction IDs instead of replaying the operation.
            plugin.getLogger().log(Level.WARNING,
                    "[Persistence] Could not compact local recovery state after a backend commit. "
                            + "The existing WAL was retained and remains safe to reconcile after a crash.", exception);
        }
    }

    /**
     * Ensures that no stale committed WAL record can predate a direct authoritative write.
     *
     * <p>Queued writes are naturally ordered after any stale record because they append their own
     * absolute post-state. Direct durable operations bypass the WAL, so they must first prove that
     * committed recovery residue has been removed. Otherwise a later restart could reconcile an
     * older absolute state over a newer direct backend write.</p>
     */
    synchronized void prepareForDirectAuthoritativeWrite() throws IOException {
        if (!pendingEntries.isEmpty()) {
            throw new IllegalStateException("Direct authoritative writes require an empty local recovery queue");
        }
        if (Files.exists(walFile) || Files.exists(temporaryWalFile)) {
            clearWalFiles();
        }
        cleanupRequired = false;
        walIntegrityUncertain = false;
    }

    private void repairCommittedResidueBeforeAppend() throws IOException {
        if (!cleanupRequired) {
            return;
        }
        if (pendingEntries.isEmpty()) {
            clearWalFiles();
        } else {
            compactPendingEntries();
        }
        cleanupRequired = false;
        walIntegrityUncertain = false;
        committedSinceCompaction = 0;
    }

    synchronized int pendingRecoveryCount() {
        return pendingEntries.size();
    }

    synchronized long recoverySizeBytes() {
        try {
            return Files.isRegularFile(walFile) ? Files.size(walFile) : 0L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    Path recoveryFile() {
        return walFile;
    }

    private void reconcileWalEntries(Storage storage, List<LocalRecoveryState> entries) throws Exception {
        if (!(storage instanceof WalletTransactionStore transactionStore)) {
            throw new IllegalStateException("Local recovery requires a wallet transaction-capable storage backend");
        }

        Map<UUID, Long> wallets = new LinkedHashMap<>(storage.getAllBalanceMinorUnits());
        Map<UUID, Long> banks = new LinkedHashMap<>(storage.getAllBankBalanceMinorUnits());
        Map<UUID, PlayerIdentity> identities = storage.getAllPlayerIdentities();
        List<TransactionRecord> existingTransactions = transactionStore.getAllWalletTransactions();
        Map<UUID, TransactionRecord> transactionsById = new LinkedHashMap<>();
        Map<String, TransactionRecord> transactionsByIdempotencyKey = new HashMap<>();
        Map<UUID, TransactionRecord> successfulRollbacks = new HashMap<>();

        for (TransactionRecord record : existingTransactions) {
            transactionsById.put(record.id(), record);
            indexUniqueness(record, transactionsByIdempotencyKey, successfulRollbacks);
        }

        Set<UUID> accountsPresentBeforeRecovery = new HashSet<>(wallets.keySet());
        Map<UUID, LocalRecoveryState.AccountInitialization> initializations = new LinkedHashMap<>();
        for (LocalRecoveryState entry : entries) {
            initializations.putAll(entry.accountInitializations());
        }

        for (Map.Entry<UUID, LocalRecoveryState.AccountInitialization> entry : initializations.entrySet()) {
            UUID playerId = entry.getKey();
            LocalRecoveryState.AccountInitialization initialization = entry.getValue();
            if (!accountsPresentBeforeRecovery.contains(playerId)) {
                wallets.putIfAbsent(playerId, initialization.startingBalanceMinor());
                banks.putIfAbsent(playerId, 0L);
                // Account creation and its opening journal row are atomic in YAML/SQLite. If the
                // account did not reach the backend, recreate the opening audit row before merging
                // the later pending transaction records.
                TransactionRecord initial = TransactionRecords.initialBalance(playerId,
                        initialization.startingBalanceMinor(), initialization.timestamp().minusMillis(1L));
                transactionsById.put(initial.id(), initial);
                indexUniqueness(initial, transactionsByIdempotencyKey, successfulRollbacks);
            }
        }

        for (LocalRecoveryState entry : entries) {
            wallets.putAll(entry.walletBalances());
            banks.putAll(entry.bankBalances());
            if (entry.walletTransaction() != null) {
                mergeRecoveryTransaction(entry.walletTransaction(), transactionsById,
                        transactionsByIdempotencyKey, successfulRollbacks);
            }
        }

        List<TransactionRecord> mergedTransactions = new ArrayList<>(transactionsById.values());
        mergedTransactions.sort(TRANSACTION_ORDER);
        storage.replaceMinorUnitSnapshot(Map.copyOf(wallets), Map.copyOf(banks),
                List.copyOf(mergedTransactions), Map.copyOf(identities));
    }

    private static void mergeRecoveryTransaction(TransactionRecord pending,
                                                   Map<UUID, TransactionRecord> transactionsById,
                                                   Map<String, TransactionRecord> transactionsByIdempotencyKey,
                                                   Map<UUID, TransactionRecord> successfulRollbacks) {
        TransactionRecord sameId = transactionsById.get(pending.id());
        if (sameId != null) {
            if (!sameDurableOutcome(sameId, pending)) {
                throw new IllegalStateException("Recovery transaction " + pending.id()
                        + " conflicts with the authoritative wallet journal");
            }
            return;
        }

        if (pending.idempotencyKey() != null) {
            TransactionRecord sameKey = transactionsByIdempotencyKey.get(pending.idempotencyKey());
            if (sameKey != null && !sameKey.id().equals(pending.id())) {
                throw new IllegalStateException("Recovery idempotency key " + pending.idempotencyKey()
                        + " belongs to a different authoritative transaction");
            }
        }
        if (pending.isSuccessful() && pending.reversalOfTransactionId() != null) {
            TransactionRecord existingRollback = successfulRollbacks.get(pending.reversalOfTransactionId());
            if (existingRollback != null && !existingRollback.id().equals(pending.id())) {
                throw new IllegalStateException("Recovery would create a second successful rollback for "
                        + pending.reversalOfTransactionId());
            }
        }

        transactionsById.put(pending.id(), pending);
        indexUniqueness(pending, transactionsByIdempotencyKey, successfulRollbacks);
    }

    private static void indexUniqueness(TransactionRecord record,
                                        Map<String, TransactionRecord> byIdempotencyKey,
                                        Map<UUID, TransactionRecord> successfulRollbacks) {
        if (record.idempotencyKey() != null) {
            TransactionRecord previous = byIdempotencyKey.putIfAbsent(record.idempotencyKey(), record);
            if (previous != null && !previous.id().equals(record.id())) {
                throw new IllegalStateException("Duplicate authoritative idempotency key " + record.idempotencyKey());
            }
        }
        if (record.isSuccessful() && record.reversalOfTransactionId() != null) {
            TransactionRecord previous = successfulRollbacks.putIfAbsent(record.reversalOfTransactionId(), record);
            if (previous != null && !previous.id().equals(record.id())) {
                throw new IllegalStateException("Duplicate successful rollback for " + record.reversalOfTransactionId());
            }
        }
    }

    private static boolean sameDurableOutcome(TransactionRecord first, TransactionRecord second) {
        return Objects.equals(first.id(), second.id())
                && Objects.equals(first.timestamp(), second.timestamp())
                && first.type() == second.type()
                && first.status() == second.status()
                && Objects.equals(first.origin(), second.origin())
                && first.operation() == second.operation()
                && Objects.equals(first.sourcePlayerId(), second.sourcePlayerId())
                && Objects.equals(first.targetPlayerId(), second.targetPlayerId())
                && first.requestedAmountMinor() == second.requestedAmountMinor()
                && first.appliedAmountMinor() == second.appliedAmountMinor()
                && Objects.equals(first.sourceBalanceBeforeMinor(), second.sourceBalanceBeforeMinor())
                && Objects.equals(first.sourceBalanceAfterMinor(), second.sourceBalanceAfterMinor())
                && Objects.equals(first.targetBalanceBeforeMinor(), second.targetBalanceBeforeMinor())
                && Objects.equals(first.targetBalanceAfterMinor(), second.targetBalanceAfterMinor())
                && Objects.equals(first.reason(), second.reason())
                && Objects.equals(first.metadata(), second.metadata())
                && Objects.equals(first.reversalOfTransactionId(), second.reversalOfTransactionId())
                && Objects.equals(first.batchId(), second.batchId())
                && Objects.equals(first.idempotencyKey(), second.idempotencyKey())
                && first.failureReason() == second.failureReason();
    }

    private void appendFrame(byte[] payload) throws IOException {
        if (payload.length <= 0 || payload.length > MAX_RECORD_BYTES) {
            throw new IOException("Local recovery record size is outside the supported range: " + payload.length);
        }
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES * 2 + payload.length);
        frame.putInt(payload.length);
        frame.putInt((int) crc.getValue());
        frame.put(payload);
        frame.flip();

        boolean createNew = !Files.exists(walFile);
        if (!createNew) {
            validateExistingWalHeader();
        }
        long existingSize = createNew ? Integer.BYTES * 2L : Files.size(walFile);
        long projectedSize = Math.addExact(existingSize, Integer.BYTES * 2L + payload.length);
        if (projectedSize > MAX_WAL_BYTES) {
            throw new IOException("Local recovery WAL would exceed the internal safety limit of "
                    + MAX_WAL_BYTES + " bytes");
        }
        long originalSize = createNew ? 0L : Files.size(walFile);
        try {
            try (FileChannel channel = createNew
                    ? FileChannel.open(walFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    : FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                if (createNew) {
                    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 2);
                    header.putInt(WAL_MAGIC).putInt(WAL_FORMAT_VERSION).flip();
                    writeFully(channel, header);
                }
                writeFully(channel, frame);
                // One force covers both the header (for a new WAL) and this record. This is the
                // acknowledgement barrier: no caller may report the local mutation as accepted until
                // its compact recovery state is durable.
                channel.force(true);
            }
            if (createNew) {
                forceParentDirectoryBestEffort();
            }
        } catch (IOException writeFailure) {
            try {
                if (createNew) {
                    Files.deleteIfExists(walFile);
                    forceParentDirectoryBestEffort();
                } else {
                    try (FileChannel rollback = FileChannel.open(walFile, StandardOpenOption.WRITE)) {
                        rollback.truncate(originalSize);
                        rollback.force(true);
                    }
                }
            } catch (IOException rollbackFailure) {
                walIntegrityUncertain = true;
                writeFailure.addSuppressed(rollbackFailure);
            }
            throw writeFailure;
        }
    }

    private void validateExistingWalHeader() throws IOException {
        if (!Files.isRegularFile(walFile) || Files.size(walFile) < Integer.BYTES * 2L) {
            throw new IOException("Existing local recovery WAL is truncated");
        }
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 2);
            readFullyOrThrow(channel, header, "local recovery WAL header");
            header.flip();
            if (header.getInt() != WAL_MAGIC) {
                throw new IOException("Invalid local recovery WAL magic");
            }
            int version = header.getInt();
            if (version != WAL_FORMAT_VERSION) {
                throw new IOException("Unsupported local recovery WAL format " + version);
            }
        }
    }

    private List<LocalRecoveryState> readWal() throws IOException {
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 2);
            readFullyOrThrow(channel, header, "local recovery WAL header");
            header.flip();
            int magic = header.getInt();
            int version = header.getInt();
            if (magic != WAL_MAGIC) {
                throw new IOException("Invalid local recovery WAL magic");
            }
            if (version != WAL_FORMAT_VERSION) {
                throw new IOException("Unsupported local recovery WAL format " + version);
            }

            List<LocalRecoveryState> entries = new ArrayList<>();
            Set<UUID> seenWriteIds = new HashSet<>();
            while (channel.position() < channel.size()) {
                ByteBuffer frameHeader = ByteBuffer.allocate(Integer.BYTES * 2);
                readFullyOrThrow(channel, frameHeader, "local recovery WAL frame header");
                frameHeader.flip();
                int length = frameHeader.getInt();
                int expectedCrc = frameHeader.getInt();
                if (length <= 0 || length > MAX_RECORD_BYTES) {
                    throw new IOException("Invalid local recovery WAL frame length " + length);
                }
                ByteBuffer payload = ByteBuffer.allocate(length);
                readFullyOrThrow(channel, payload, "local recovery WAL frame payload");
                byte[] bytes = payload.array();
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if ((int) crc.getValue() != expectedCrc) {
                    throw new IOException("Local recovery WAL checksum mismatch");
                }
                LocalRecoveryState state = decodeState(bytes);
                if (!seenWriteIds.add(state.writeId())) {
                    throw new IOException("Duplicate local recovery WAL write ID " + state.writeId());
                }
                entries.add(state);
            }
            return List.copyOf(entries);
        }
    }

    private void compactPendingEntries() throws IOException {
        ensureParentDirectory();
        try (FileChannel channel = FileChannel.open(temporaryWalFile, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES * 2);
            header.putInt(WAL_MAGIC).putInt(WAL_FORMAT_VERSION).flip();
            writeFully(channel, header);
            for (LocalRecoveryState state : pendingEntries.values()) {
                byte[] payload = encodeState(state);
                CRC32 crc = new CRC32();
                crc.update(payload);
                ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES * 2 + payload.length);
                frame.putInt(payload.length).putInt((int) crc.getValue()).put(payload).flip();
                writeFully(channel, frame);
            }
            channel.force(true);
        }
        moveAtomically(temporaryWalFile, walFile);
        forceParentDirectoryBestEffort();
    }

    private byte[] encodeState(LocalRecoveryState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeUuid(out, state.writeId());
            out.writeLong(state.enqueuedAt().toEpochMilli());
            writeBalanceMap(out, state.walletBalances());
            writeBalanceMap(out, state.bankBalances());
            out.writeBoolean(state.walletTransaction() != null);
            if (state.walletTransaction() != null) {
                writeTransaction(out, state.walletTransaction());
            }
            if (state.accountInitializations().size() > MAX_COLLECTION_ENTRIES) {
                throw new IOException("Too many account initializations in local recovery record");
            }
            out.writeInt(state.accountInitializations().size());
            for (Map.Entry<UUID, LocalRecoveryState.AccountInitialization> entry
                    : state.accountInitializations().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                writeUuid(out, entry.getKey());
                out.writeLong(entry.getValue().startingBalanceMinor());
                out.writeLong(entry.getValue().timestamp().toEpochMilli());
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_RECORD_BYTES) {
            throw new IOException("Local recovery record exceeds " + MAX_RECORD_BYTES + " bytes");
        }
        return payload;
    }

    private LocalRecoveryState decodeState(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            UUID writeId = readUuid(in);
            Instant enqueuedAt = Instant.ofEpochMilli(in.readLong());
            Map<UUID, Long> wallets = readBalanceMap(in);
            Map<UUID, Long> banks = readBalanceMap(in);
            TransactionRecord transaction = in.readBoolean() ? readTransaction(in) : null;
            int initializationCount = readCollectionCount(in, "account initializations");
            Map<UUID, LocalRecoveryState.AccountInitialization> initializations = new LinkedHashMap<>();
            for (int index = 0; index < initializationCount; index++) {
                UUID playerId = readUuid(in);
                long startingBalanceMinor = in.readLong();
                Instant timestamp = Instant.ofEpochMilli(in.readLong());
                if (initializations.put(playerId,
                        new LocalRecoveryState.AccountInitialization(startingBalanceMinor, timestamp)) != null) {
                    throw new IOException("Duplicate recovery account initialization " + playerId);
                }
            }
            if (in.available() != 0) {
                throw new IOException("Unexpected trailing bytes in local recovery record");
            }
            return LocalRecoveryState.of(writeId, enqueuedAt, wallets, banks, transaction, initializations);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid value in local recovery record", exception);
        }
    }

    private static void writeBalanceMap(DataOutputStream out, Map<UUID, Long> values) throws IOException {
        if (values.size() > MAX_COLLECTION_ENTRIES) {
            throw new IOException("Too many balances in local recovery record");
        }
        out.writeInt(values.size());
        for (Map.Entry<UUID, Long> entry : values.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            writeUuid(out, entry.getKey());
            out.writeLong(entry.getValue());
        }
    }

    private static Map<UUID, Long> readBalanceMap(DataInputStream in) throws IOException {
        int count = readCollectionCount(in, "balances");
        Map<UUID, Long> values = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            UUID playerId = readUuid(in);
            long amount = in.readLong();
            if (amount < 0L) {
                throw new IOException("Negative balance in local recovery record");
            }
            if (values.put(playerId, amount) != null) {
                throw new IOException("Duplicate balance account in local recovery record: " + playerId);
            }
        }
        return Map.copyOf(values);
    }

    private static void writeTransaction(DataOutputStream out, TransactionRecord record) throws IOException {
        writeUuid(out, record.id());
        out.writeLong(record.timestamp().toEpochMilli());
        writeString(out, record.type().name());
        writeString(out, record.status().name());
        writeString(out, record.origin().type().name());
        writeNullableUuid(out, record.origin().playerId());
        writeString(out, record.origin().identifier());
        writeString(out, record.operation().name());
        writeNullableUuid(out, record.sourcePlayerId());
        writeNullableUuid(out, record.targetPlayerId());
        out.writeLong(record.requestedAmountMinor());
        out.writeLong(record.appliedAmountMinor());
        writeNullableLong(out, record.sourceBalanceBeforeMinor());
        writeNullableLong(out, record.sourceBalanceAfterMinor());
        writeNullableLong(out, record.targetBalanceBeforeMinor());
        writeNullableLong(out, record.targetBalanceAfterMinor());
        writeNullableString(out, record.reason());
        if (record.metadata().size() > MAX_COLLECTION_ENTRIES) {
            throw new IOException("Too many transaction metadata entries in local recovery record");
        }
        out.writeInt(record.metadata().size());
        for (Map.Entry<String, String> entry : record.metadata().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
        writeNullableUuid(out, record.reversalOfTransactionId());
        writeNullableUuid(out, record.batchId());
        writeNullableString(out, record.idempotencyKey());
        writeString(out, record.failureReason().name());
        writeNullableString(out, record.failureDetail());
    }

    private static TransactionRecord readTransaction(DataInputStream in) throws IOException {
        try {
            UUID id = readUuid(in);
            Instant timestamp = Instant.ofEpochMilli(in.readLong());
            TransactionType type = TransactionType.valueOf(readString(in));
            TransactionStatus status = TransactionStatus.valueOf(readString(in));
            TransactionOriginType originType = TransactionOriginType.valueOf(readString(in));
            UUID originPlayerId = readNullableUuid(in);
            String originIdentifier = readString(in);
            TransactionOrigin origin = new TransactionOrigin(originType, originPlayerId, originIdentifier);
            WalletOperation operation = WalletOperation.valueOf(readString(in));
            UUID sourcePlayerId = readNullableUuid(in);
            UUID targetPlayerId = readNullableUuid(in);
            long requestedAmount = in.readLong();
            long appliedAmount = in.readLong();
            Long sourceBefore = readNullableLong(in);
            Long sourceAfter = readNullableLong(in);
            Long targetBefore = readNullableLong(in);
            Long targetAfter = readNullableLong(in);
            String reason = readNullableString(in);
            int metadataCount = readCollectionCount(in, "transaction metadata");
            Map<String, String> metadata = new LinkedHashMap<>();
            for (int index = 0; index < metadataCount; index++) {
                String key = readString(in);
                String value = readString(in);
                if (metadata.put(key, value) != null) {
                    throw new IOException("Duplicate transaction metadata key " + key);
                }
            }
            UUID reversal = readNullableUuid(in);
            UUID batchId = readNullableUuid(in);
            String idempotencyKey = readNullableString(in);
            TransactionFailureReason failureReason = TransactionFailureReason.valueOf(readString(in));
            String failureDetail = readNullableString(in);
            return new TransactionRecord(id, timestamp, type, status, origin, operation, sourcePlayerId,
                    targetPlayerId, requestedAmount, appliedAmount, sourceBefore, sourceAfter, targetBefore,
                    targetAfter, reason, metadata, reversal, batchId, idempotencyKey, failureReason, failureDetail);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid transaction value in local recovery record", exception);
        }
    }

    private static int readCollectionCount(DataInputStream in, String name) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_COLLECTION_ENTRIES) {
            throw new IOException("Invalid " + name + " count " + count);
        }
        return count;
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeNullableUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeUuid(out, value);
    }

    private static UUID readNullableUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? readUuid(in) : null;
    }

    private static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeLong(value);
    }

    private static Long readNullableLong(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readLong() : null;
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeString(out, value);
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("Recovery string exceeds " + MAX_STRING_BYTES + " bytes");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid recovery string length " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Truncated recovery string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void restoreLegacySnapshot(Storage storage) throws Exception {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(legacyFile);
        int version = cfg.getInt(LEGACY_FORMAT_VERSION_PATH, 0);
        if (version != LEGACY_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported legacy local persistence recovery format " + version);
        }
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(readLegacyBalances(cfg, LEGACY_BALANCES_SECTION),
                readLegacyBalances(cfg, LEGACY_BANK_BALANCES_SECTION), readLegacyTransactions(cfg));
        plugin.getLogger().warning("[Persistence] Legacy local recovery snapshot found. Restoring "
                + snapshot.balances().size() + " wallet balance(s), " + snapshot.bankBalances().size()
                + " bank balance(s), and " + snapshot.walletTransactions().size()
                + " wallet transaction(s) before migrating to the bounded recovery WAL.");
        storage.replaceMinorUnitSnapshot(snapshot.balances(), snapshot.bankBalances(), snapshot.walletTransactions());
        Files.deleteIfExists(legacyFile.toPath());
        cleanupLegacyTemporaryFile();
        forceParentDirectoryBestEffort();
        plugin.getLogger().info("[Persistence] Legacy local recovery snapshot restored and removed.");
    }

    private static Map<UUID, Long> readLegacyBalances(YamlConfiguration cfg, String section) {
        ConfigurationSection values = cfg.getConfigurationSection(section);
        if (values == null) return Map.of();
        Map<UUID, Long> balances = new LinkedHashMap<>();
        for (String key : values.getKeys(false)) {
            UUID playerId = UUID.fromString(key);
            Object raw = values.get(key);
            if (!(raw instanceof Number number)) {
                throw new IllegalStateException("Invalid legacy recovery balance for " + playerId);
            }
            long amount = number.longValue();
            if (amount < 0L) {
                throw new IllegalStateException("Negative legacy recovery balance for " + playerId);
            }
            balances.put(playerId, amount);
        }
        return Map.copyOf(balances);
    }

    private static List<TransactionRecord> readLegacyTransactions(YamlConfiguration cfg) {
        ConfigurationSection section = cfg.getConfigurationSection(LEGACY_WALLET_TRANSACTIONS_SECTION);
        if (section == null) return List.of();
        List<TransactionRecord> records = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String base = LEGACY_WALLET_TRANSACTIONS_SECTION + "." + key;
            UUID transactionId = UUID.fromString(key);
            Instant timestamp = Instant.parse(requireLegacyString(cfg, base + ".timestamp"));
            TransactionType type = TransactionType.valueOf(requireLegacyString(cfg, base + ".type"));
            TransactionStatus status = TransactionStatus.valueOf(requireLegacyString(cfg, base + ".status"));
            TransactionOrigin origin = readLegacyOrigin(cfg, base + ".origin");
            WalletOperation operation = WalletOperation.valueOf(requireLegacyString(cfg, base + ".operation"));
            UUID sourcePlayerId = readLegacyOptionalUuid(cfg, base + ".source_player_id");
            UUID targetPlayerId = readLegacyOptionalUuid(cfg, base + ".target_player_id");
            long requestedAmount = requireLegacyLong(cfg, base + ".requested_amount_minor");
            long appliedAmount = requireLegacyLong(cfg, base + ".applied_amount_minor");
            Long sourceBefore = legacyOptionalLong(cfg, base + ".source_balance_before_minor");
            Long sourceAfter = legacyOptionalLong(cfg, base + ".source_balance_after_minor");
            Long targetBefore = legacyOptionalLong(cfg, base + ".target_balance_before_minor");
            Long targetAfter = legacyOptionalLong(cfg, base + ".target_balance_after_minor");
            String reason = cfg.getString(base + ".reason");
            Map<String, String> metadata = readLegacyMetadata(cfg, base + ".metadata");
            UUID reversal = readLegacyOptionalUuid(cfg, base + ".reversal_of_transaction_id");
            UUID batchId = readLegacyOptionalUuid(cfg, base + ".batch_id");
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
        return List.copyOf(records);
    }

    private static TransactionOrigin readLegacyOrigin(YamlConfiguration cfg, String path) {
        TransactionOriginType type = TransactionOriginType.valueOf(requireLegacyString(cfg, path + ".type"));
        UUID playerId = readLegacyOptionalUuid(cfg, path + ".player_id");
        return new TransactionOrigin(type, playerId, requireLegacyString(cfg, path + ".identifier"));
    }

    private static String requireLegacyString(YamlConfiguration cfg, String path) {
        String value = cfg.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing legacy recovery value: " + path);
        }
        return value;
    }

    private static long requireLegacyLong(YamlConfiguration cfg, String path) {
        Long value = legacyOptionalLong(cfg, path);
        if (value == null) {
            throw new IllegalStateException("Missing legacy recovery value: " + path);
        }
        return value;
    }

    private static Long legacyOptionalLong(YamlConfiguration cfg, String path) {
        if (!cfg.contains(path)) return null;
        Object raw = cfg.get(path);
        if (!(raw instanceof Number number)) {
            throw new IllegalStateException("Invalid legacy recovery integer: " + path);
        }
        return number.longValue();
    }

    private static UUID readLegacyOptionalUuid(YamlConfiguration cfg, String path) {
        String value = cfg.getString(path);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static Map<String, String> readLegacyMetadata(YamlConfiguration cfg, String path) {
        Object raw = cfg.get(path);
        if (raw == null) return Map.of();
        if (!(raw instanceof List<?> entries)) {
            throw new IllegalStateException("Invalid legacy recovery metadata: " + path);
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map) || map.get("key") == null || map.get("value") == null) {
                throw new IllegalStateException("Invalid legacy recovery metadata entry: " + path);
            }
            metadata.put(String.valueOf(map.get("key")), String.valueOf(map.get("value")));
        }
        return Map.copyOf(metadata);
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = walFile.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
            forceParentDirectoryBestEffort();
        }
    }

    private void clearWalFiles() throws IOException {
        Files.deleteIfExists(walFile);
        Files.deleteIfExists(temporaryWalFile);
        forceParentDirectoryBestEffort();
    }

    private void cleanupTemporaryWal() throws IOException {
        Files.deleteIfExists(temporaryWalFile);
    }

    private void cleanupLegacyTemporaryFile() throws IOException {
        Files.deleteIfExists(legacyTemporaryFile.toPath());
    }

    private void forceParentDirectoryBestEffort() {
        Path parent = walFile.getParent();
        if (parent == null || !Files.isDirectory(parent)) return;
        try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not uniformly available on every JVM/filesystem (notably Windows).
            // File contents themselves are still forced before rename; do not claim stronger
            // hardware-level guarantees than Java and the host filesystem provide.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            // Compaction is only an optimization. Never replace the last known-good WAL through a
            // non-atomic move because a crash during that replacement could destroy recovery state.
            Files.deleteIfExists(source);
            throw new IOException("Atomic local recovery WAL compaction is not supported by this filesystem", exception);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void readFullyOrThrow(FileChannel channel, ByteBuffer buffer, String description) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new EOFException("Truncated " + description);
            }
        }
    }
}
