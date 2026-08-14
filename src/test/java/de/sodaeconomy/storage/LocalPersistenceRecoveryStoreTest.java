package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPersistenceRecoveryStoreTest extends MockBukkitTestBase {

    @Test
    void walRoundTripPreservesExactMinorUnitsBeyondDoublePrecisionAndJournalIdentity() throws Exception {
        UUID playerId = UUID.randomUUID();
        long before = 9_007_199_254_740_000L;
        long after = 9_007_199_254_740_993L;
        TransactionRecord record = successfulCredit(playerId, before, after, UUID.randomUUID(), "recovery-exact");

        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), Instant.now(), Map.of(playerId, after),
                Map.of(), record, Map.of()));

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        backend.walletBalances.put(playerId, before);
        LocalPersistenceRecoveryStore reader = new LocalPersistenceRecoveryStore(plugin);
        reader.restoreIfPresent(backend);

        assertEquals(after, backend.walletBalances.get(playerId).longValue());
        assertEquals(List.of(record), backend.transactions);
        assertFalse(Files.exists(reader.recoveryFile()));
    }

    @Test
    void staleWalAfterBackendCommitIsReconciledIdempotentlyWithoutDuplicateJournalRows() throws Exception {
        UUID playerId = UUID.randomUUID();
        TransactionRecord record = successfulCredit(playerId, 1_000L, 1_500L, UUID.randomUUID(), "already-committed");
        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        backend.walletBalances.put(playerId, 1_500L);
        backend.transactions.add(record);

        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), Instant.now(), Map.of(playerId, 1_500L),
                Map.of(), record, Map.of()));

        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(1_500L, backend.walletBalances.get(playerId).longValue());
        assertEquals(1, backend.transactions.size());
        assertEquals(record, backend.transactions.get(0));
    }

    @Test
    void pendingAccountInitializationRecoversOpeningJournalExactlyOnceAcrossRepeatedRecovery() throws Exception {
        UUID playerId = UUID.randomUUID();
        long startingBalance = 2_500L;
        Instant timestamp = Instant.parse("2026-08-14T12:00:00Z");
        LocalRecoveryState state = LocalRecoveryState.of(UUID.randomUUID(), timestamp,
                Map.of(playerId, startingBalance), Map.of(playerId, 0L), null,
                Map.of(playerId, new LocalRecoveryState.AccountInitialization(startingBalance, timestamp)));

        LocalPersistenceRecoveryStore firstWriter = new LocalPersistenceRecoveryStore(plugin);
        firstWriter.appendPending(state);
        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(startingBalance, backend.walletBalances.get(playerId).longValue());
        assertEquals(1, backend.transactions.size());
        assertEquals(TransactionType.INITIAL_BALANCE, backend.transactions.get(0).type());

        // Simulate a crash after reconciliation committed but before recovery evidence was cleaned.
        LocalPersistenceRecoveryStore staleWriter = new LocalPersistenceRecoveryStore(plugin);
        staleWriter.appendPending(state);
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(1, backend.transactions.size(),
                "Repeated recovery must not synthesize another opening-balance journal row");
        assertEquals(startingBalance, backend.walletBalances.get(playerId).longValue());
    }

    @Test
    void transferRecoveryAppliesBothAbsoluteWalletSidesAsOneReconciliationUnit() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        long sourceBefore = 10_000L;
        long targetBefore = 2_000L;
        long amount = 3_000L;
        WalletTransactionRequest request = new WalletTransactionRequest(UUID.randomUUID(), Instant.now(),
                TransactionType.API_TRANSFER, TransactionOrigin.api("LocalPersistenceRecoveryStoreTest"),
                WalletOperation.TRANSFER, source, target, amount, null, "Recovery transfer", Map.of(),
                null, null, "recovery-transfer");
        TransactionRecord record = TransactionRecords.successful(request, amount, sourceBefore,
                sourceBefore - amount, targetBefore, targetBefore + amount);

        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), record.timestamp(),
                Map.of(source, sourceBefore - amount, target, targetBefore + amount), Map.of(), record, Map.of()));

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        backend.walletBalances.put(source, sourceBefore);
        backend.walletBalances.put(target, targetBefore);
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(sourceBefore - amount, backend.walletBalances.get(source).longValue());
        assertEquals(targetBefore + amount, backend.walletBalances.get(target).longValue());
        assertEquals(sourceBefore + targetBefore, backend.walletBalances.values().stream().mapToLong(Long::longValue).sum(),
                "Recovery of a transfer must conserve total money");
        assertEquals(List.of(record), backend.transactions);
    }

    @Test
    void walletToBankRecoveryKeepsWalletAndBankStateConsistent() throws Exception {
        UUID playerId = UUID.randomUUID();
        long walletBefore = 8_000L;
        long bankBefore = 1_000L;
        long amount = 2_500L;
        WalletTransactionRequest request = new WalletTransactionRequest(UUID.randomUUID(), Instant.now(),
                TransactionType.WALLET_TO_BANK, TransactionOrigin.system("Recovery bank test"),
                WalletOperation.DEBIT, playerId, null, amount, null, "Wallet to bank", Map.of(),
                null, null, "recovery-wallet-bank");
        TransactionRecord record = TransactionRecords.successful(request, amount, walletBefore,
                walletBefore - amount, null, null);

        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), record.timestamp(),
                Map.of(playerId, walletBefore - amount), Map.of(playerId, bankBefore + amount), record, Map.of()));

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        backend.walletBalances.put(playerId, walletBefore);
        backend.bankBalances.put(playerId, bankBefore);
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(walletBefore - amount, backend.walletBalances.get(playerId).longValue());
        assertEquals(bankBefore + amount, backend.bankBalances.get(playerId).longValue());
        assertEquals(walletBefore + bankBefore, backend.walletBalances.get(playerId) + backend.bankBalances.get(playerId),
                "Wallet-to-bank recovery must conserve the player's combined funds");
        assertEquals(List.of(record), backend.transactions);
    }

    @Test
    void truncatedWalFailsClosedAndPreservesTheRecoveryEvidence() throws Exception {
        UUID playerId = UUID.randomUUID();
        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), Instant.now(), Map.of(playerId, 500L),
                Map.of(), null, Map.of()));
        Path wal = writer.recoveryFile();
        long originalSize = Files.size(wal);
        try (FileChannel channel = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            channel.truncate(originalSize - 1L);
            channel.force(true);
        }

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        assertThrows(Exception.class, () -> new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend));
        assertTrue(Files.exists(wal), "Corrupt recovery evidence must not be silently discarded");
    }

    @Test
    void interruptedCompactionTempFileCannotOverrideTheLastKnownGoodWal() throws Exception {
        UUID playerId = UUID.randomUUID();
        LocalPersistenceRecoveryStore writer = new LocalPersistenceRecoveryStore(plugin);
        writer.appendPending(LocalRecoveryState.of(UUID.randomUUID(), Instant.now(), Map.of(playerId, 700L),
                Map.of(), null, Map.of()));
        Path temp = writer.recoveryFile().resolveSibling("local-persistence-recovery.wal.tmp");
        Files.writeString(temp, "partial-compaction-data", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        backend.walletBalances.put(playerId, 100L);
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(700L, backend.walletBalances.get(playerId).longValue());
        assertFalse(Files.exists(writer.recoveryFile()));
        assertFalse(Files.exists(temp));
    }

    @Test
    void legacyVersionOneYamlSnapshotIsRestoredOnceAndRemoved() throws Exception {
        UUID playerId = UUID.randomUUID();
        long wallet = 12_345L;
        long bank = 67_890L;
        File legacy = new File(plugin.getDataFolder(), "local-persistence-recovery.yml");
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("format_version", 1);
        cfg.set("balances." + playerId, wallet);
        cfg.set("bank_balances." + playerId, bank);
        cfg.save(legacy);

        ExactRecoveryStorage backend = new ExactRecoveryStorage();
        new LocalPersistenceRecoveryStore(plugin).restoreIfPresent(backend);

        assertEquals(wallet, backend.walletBalances.get(playerId).longValue());
        assertEquals(bank, backend.bankBalances.get(playerId).longValue());
        assertFalse(legacy.exists());
    }

    @Test
    void compactionKeepsContinuousWalBoundedByOutstandingWorkAndDrainRemovesIt() throws Exception {
        LocalPersistenceRecoveryStore store = new LocalPersistenceRecoveryStore(plugin);
        List<UUID> writeIds = new ArrayList<>();
        UUID playerId = UUID.randomUUID();
        for (int i = 0; i < 512; i++) {
            UUID writeId = UUID.randomUUID();
            writeIds.add(writeId);
            store.appendPending(LocalRecoveryState.of(writeId, Instant.ofEpochMilli(i + 1L),
                    Map.of(playerId, (long) i), Map.of(), null, Map.of()));
        }
        long fullBacklogBytes = store.recoverySizeBytes();

        for (int i = 0; i < 256; i++) {
            store.markCommitted(writeIds.get(i));
        }

        long compactedBytes = store.recoverySizeBytes();
        assertEquals(256, store.pendingRecoveryCount());
        assertTrue(compactedBytes > 0L && compactedBytes < fullBacklogBytes,
                "Compaction must drop committed history and retain only unresolved recovery work");

        for (int i = 256; i < writeIds.size(); i++) {
            store.markCommitted(writeIds.get(i));
        }
        assertEquals(0, store.pendingRecoveryCount());
        assertEquals(0L, store.recoverySizeBytes());
        assertFalse(Files.exists(store.recoveryFile()));
    }

    private static TransactionRecord successfulCredit(UUID playerId, long before, long after, UUID transactionId,
                                                        String idempotencyKey) {
        WalletTransactionRequest request = new WalletTransactionRequest(transactionId, Instant.now(),
                TransactionType.API_DEPOSIT, TransactionOrigin.api("LocalPersistenceRecoveryStoreTest"),
                WalletOperation.CREDIT, null, playerId, after - before, null, "Recovery test", Map.of(),
                null, null, idempotencyKey);
        return TransactionRecords.successful(request, after - before, null, null, before, after);
    }

    /** Minimal exact storage double for recovery reconciliation tests. */
    private static final class ExactRecoveryStorage implements Storage, WalletTransactionStore {
        private final Map<UUID, Long> walletBalances = new LinkedHashMap<>();
        private final Map<UUID, Long> bankBalances = new LinkedHashMap<>();
        private final List<TransactionRecord> transactions = new ArrayList<>();
        private final Map<UUID, PlayerIdentity> identities = new LinkedHashMap<>();

        @Override public void init(JavaPlugin plugin) { }
        @Override public Double getBalance(UUID uuid) { Long value = walletBalances.get(uuid); return value == null ? null : Money.fromMinorUnits(value); }
        @Override public double getOrCreateBalance(UUID uuid, double initialBalance) { return Money.fromMinorUnits(walletBalances.computeIfAbsent(uuid, ignored -> Money.toMinorUnits(initialBalance))); }
        @Override public void setBalance(UUID uuid, double amount) { walletBalances.put(uuid, Money.toMinorUnits(amount)); }
        @Override public Map<UUID, Double> getAllBalances() { Map<UUID, Double> values = new LinkedHashMap<>(); walletBalances.forEach((id, value) -> values.put(id, Money.fromMinorUnits(value))); return Map.copyOf(values); }
        @Override public Map<UUID, Long> getAllBalanceMinorUnits() { return Map.copyOf(walletBalances); }
        @Override public void saveAll(Map<UUID, Double> balances) { balances.forEach(this::setBalance); }
        @Override public Double getBankBalance(UUID uuid) { Long value = bankBalances.get(uuid); return value == null ? null : Money.fromMinorUnits(value); }
        @Override public void setBankBalance(UUID uuid, double amount) { bankBalances.put(uuid, Money.toMinorUnits(amount)); }
        @Override public Map<UUID, Double> getAllBankBalances() { Map<UUID, Double> values = new LinkedHashMap<>(); bankBalances.forEach((id, value) -> values.put(id, Money.fromMinorUnits(value))); return Map.copyOf(values); }
        @Override public Map<UUID, Long> getAllBankBalanceMinorUnits() { return Map.copyOf(bankBalances); }
        @Override public void saveAllBank(Map<UUID, Double> balances) { balances.forEach(this::setBankBalance); }
        @Override public void saveAllBankMinorUnits(Map<UUID, Long> balances) { bankBalances.putAll(balances); }
        @Override public Map<UUID, PlayerIdentity> getAllPlayerIdentities() { return Map.copyOf(identities); }
        @Override public void replacePlayerIdentities(Map<UUID, PlayerIdentity> values) { identities.clear(); identities.putAll(values); }
        @Override public void replaceMinorUnitSnapshot(Map<UUID, Long> wallets, Map<UUID, Long> banks,
                                                       List<TransactionRecord> records,
                                                       Map<UUID, PlayerIdentity> playerIdentities) {
            walletBalances.clear(); walletBalances.putAll(wallets);
            bankBalances.clear(); bankBalances.putAll(banks);
            transactions.clear(); transactions.addAll(records);
            identities.clear(); identities.putAll(playerIdentities);
        }
        @Override public boolean transferMain(UUID source, UUID target, double amount) { throw new UnsupportedOperationException(); }
        @Override public boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) { throw new UnsupportedOperationException(); }
        @Override public void close() { }
        @Override public boolean isDebug() { return false; }
        @Override public WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp) { throw new UnsupportedOperationException(); }
        @Override public TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor) { throw new UnsupportedOperationException(); }
        @Override public TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank, long maximumBalanceMinor) { throw new UnsupportedOperationException(); }
        @Override public Optional<TransactionRecord> findTransaction(UUID transactionId) { return transactions.stream().filter(record -> record.id().equals(transactionId)).findFirst(); }
        @Override public TransactionPage findTransactions(TransactionQuery query) { throw new UnsupportedOperationException(); }
        @Override public EconomyStatistics getEconomyStatistics() { throw new UnsupportedOperationException(); }
        @Override public List<TransactionRecord> getAllWalletTransactions() { return List.copyOf(transactions); }
    }
}
