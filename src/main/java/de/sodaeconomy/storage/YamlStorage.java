package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.transaction.EconomyAnalytics;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.PlayerTransactionStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * File-based storage. Wallet balances and the immutable wallet journal are deliberately kept in
 * the same YAML document, so a single atomic replacement commits or rejects both together.
 */
@SuppressWarnings("removal")
public class YamlStorage implements Storage, WalletTransactionStore {
    private static final int STORAGE_FORMAT_VERSION = 3;
    /** Canonical exact persisted balances. Legacy major-unit sections are migration input only. */
    private static final String BALANCES_SECTION = "wallet_balances_minor";
    private static final String BANK_BALANCES_SECTION = "bank_balances_minor";
    private static final String LEGACY_BALANCES_SECTION = "balances";
    private static final String LEGACY_BANK_BALANCES_SECTION = "bank_balances";
    private static final String WALLET_TRANSACTIONS_SECTION = "wallet_transactions";
    private static final String PLAYER_IDENTITIES_SECTION = "player_identities";
    private static final String STORAGE_FORMAT_VERSION_PATH = "storage_format_version";
    private static final String LEDGER_BOOTSTRAP_COMPLETED_PATH = "wallet_ledger_bootstrap_completed";
    private static final Comparator<TransactionRecord> DESCENDING_TRANSACTION_ORDER =
            Comparator.comparing(TransactionRecord::timestamp).reversed()
                    .thenComparing(TransactionRecord::id, Comparator.reverseOrder());
    private static final Comparator<TransactionRecord> ASCENDING_TRANSACTION_ORDER =
            Comparator.comparing(TransactionRecord::timestamp)
                    .thenComparing(TransactionRecord::id);

    private JavaPlugin plugin;
    private File file;
    private YamlConfiguration cfg;
    private boolean debug;
    private final Set<String> malformedTransactionWarnings = new HashSet<>();

    @Override
    public synchronized void init(JavaPlugin plugin) throws Exception {
        this.plugin = plugin;
        debug = plugin.getConfig().getBoolean("debug.storage", false);
        File folder = new File(plugin.getDataFolder(), "storage");
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Cannot create storage directory");
        }
        file = new File(folder, "balances.yml");
        if (!file.exists() && !file.createNewFile()) {
            throw new IllegalStateException("Cannot create YAML storage");
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        malformedTransactionWarnings.clear();
        upgradeWalletLedgerFormat();
    }

    @Override
    public synchronized Double getBalance(UUID uuid) {
        return getNullable(BALANCES_SECTION, uuid);
    }

    @Override
    public synchronized Long getBalanceMinorUnits(UUID uuid) {
        StorageAccessGuard.requireInternalCaller("YamlStorage.getBalanceMinorUnits", YamlStorage.class);
        WalletBalance balance = readNonNegativeBalance(BALANCES_SECTION, uuid);
        return balance.exists() && balance.valid() ? balance.minor() : null;
    }

    @Override
    public synchronized double getOrCreateBalance(UUID uuid, double initial) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.getOrCreateBalance", YamlStorage.class);
        long startingBalanceMinor = Money.toMinorUnits(initial);
        return Money.fromMinorUnits(ensureWalletAccount(uuid, startingBalanceMinor, Instant.now()).balanceMinor());
    }

    @Override
    public synchronized void setBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.setBalance", YamlStorage.class);
        putAndPersist(path(BALANCES_SECTION, uuid), Money.toMinorUnits(amount));
    }

    @Override
    public synchronized Map<UUID, Double> getAllBalances() {
        return readAll(BALANCES_SECTION);
    }

    @Override
    public synchronized Map<UUID, Long> getAllBalanceMinorUnits() {
        StorageAccessGuard.requireInternalCaller("YamlStorage.getAllBalanceMinorUnits", YamlStorage.class);
        return readAllMinor(BALANCES_SECTION);
    }

    @Override
    public synchronized void saveAll(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.saveAll", YamlStorage.class);
        persistMutation(() -> {
            for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
                cfg.set(path(BALANCES_SECTION, entry.getKey()), Money.toMinorUnits(entry.getValue()));
            }
        });
    }

    @Override
    public synchronized Map<UUID, PlayerIdentity> getAllPlayerIdentities() {
        StorageAccessGuard.requireInternalCaller("YamlStorage.getAllPlayerIdentities", YamlStorage.class);
        Map<UUID, PlayerIdentity> result = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection(PLAYER_IDENTITIES_SECTION);
        if (section == null) return Map.of();
        for (String key : section.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                PlayerIdentity identity = readPlayerIdentity(playerId);
                if (identity != null) result.put(playerId, identity);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid player identity in YAML storage: " + key, exception);
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public synchronized Optional<PlayerIdentity> findPlayerIdentity(UUID playerId) {
        StorageAccessGuard.requireInternalCaller("YamlStorage.findPlayerIdentity", YamlStorage.class);
        return Optional.ofNullable(readPlayerIdentity(playerId));
    }

    @Override
    public synchronized List<PlayerIdentity> findPlayerIdentitiesByNormalizedName(String normalizedName) {
        StorageAccessGuard.requireInternalCaller("YamlStorage.findPlayerIdentitiesByNormalizedName", YamlStorage.class);
        return getAllPlayerIdentities().values().stream()
                .filter(identity -> identity.normalizedName().equals(normalizedName))
                .toList();
    }

    @Override
    public synchronized Map<UUID, PlayerIdentity> findPlayerIdentities(Set<UUID> playerIds) {
        StorageAccessGuard.requireInternalCaller("YamlStorage.findPlayerIdentities", YamlStorage.class);
        Map<UUID, PlayerIdentity> result = new LinkedHashMap<>();
        for (UUID playerId : playerIds) {
            PlayerIdentity identity = readPlayerIdentity(playerId);
            if (identity != null) result.put(playerId, identity);
        }
        return Map.copyOf(result);
    }

    @Override
    public synchronized void upsertPlayerIdentity(PlayerIdentity identity) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.upsertPlayerIdentity", YamlStorage.class);
        PlayerIdentity effective = PlayerIdentity.merge(readPlayerIdentity(identity.playerId()), identity);
        persistMutation(() -> writePlayerIdentity(effective));
    }

    @Override
    public synchronized void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.replacePlayerIdentities", YamlStorage.class);
        persistMutation(() -> {
            cfg.set(PLAYER_IDENTITIES_SECTION, null);
            identities.values().forEach(this::writePlayerIdentity);
        });
    }

    @Override
    public synchronized Double getBankBalance(UUID uuid) {
        return getNullable(BANK_BALANCES_SECTION, uuid);
    }

    @Override
    public synchronized void setBankBalance(UUID uuid, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.setBankBalance", YamlStorage.class);
        putAndPersist(path(BANK_BALANCES_SECTION, uuid), Money.toMinorUnits(amount));
    }

    @Override
    public synchronized Map<UUID, Double> getAllBankBalances() {
        return readAll(BANK_BALANCES_SECTION);
    }

    @Override
    public synchronized Map<UUID, Long> getAllBankBalanceMinorUnits() {
        StorageAccessGuard.requireInternalCaller("YamlStorage.getAllBankBalanceMinorUnits", YamlStorage.class);
        return readAllMinor(BANK_BALANCES_SECTION);
    }

    @Override
    public synchronized void saveAllBank(Map<UUID, Double> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.saveAllBank", YamlStorage.class);
        persistMutation(() -> {
            for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
                cfg.set(path(BANK_BALANCES_SECTION, entry.getKey()), Money.toMinorUnits(entry.getValue()));
            }
        });
    }

    @Override
    public synchronized void saveAllBankMinorUnits(Map<UUID, Long> balances) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.saveAllBankMinorUnits", YamlStorage.class);
        persistMutation(() -> {
            for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                requireNonNegative(entry.getValue(), "Bank balances must not be negative");
                cfg.set(path(BANK_BALANCES_SECTION, entry.getKey()), entry.getValue());
            }
        });
    }

    @Override
    public synchronized void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.replaceSnapshot", YamlStorage.class);
        Map<UUID, Long> balances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : snapshot.balances().entrySet()) {
            balances.put(entry.getKey(), Money.toMinorUnits(entry.getValue()));
        }
        Map<UUID, Long> bankBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : snapshot.bankBalances().entrySet()) {
            bankBalances.put(entry.getKey(), Money.toMinorUnits(entry.getValue()));
        }
        replaceMinorUnitSnapshot(balances, bankBalances, snapshot.walletTransactions());
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
        StorageAccessGuard.requireInternalCaller("YamlStorage.replaceMinorUnitSnapshot", YamlStorage.class);
        ExactStorageSnapshot snapshot = new ExactStorageSnapshot(balances, bankBalances, walletTransactions,
                playerIdentities);
        persistMutation(() -> {
            cfg.set(BALANCES_SECTION, null);
            cfg.set(BANK_BALANCES_SECTION, null);
            cfg.set(LEGACY_BALANCES_SECTION, null);
            cfg.set(LEGACY_BANK_BALANCES_SECTION, null);
            cfg.set(WALLET_TRANSACTIONS_SECTION, null);
            cfg.set(PLAYER_IDENTITIES_SECTION, null);
            for (Map.Entry<UUID, Long> entry : snapshot.balances().entrySet()) {
                requireNonNegative(entry.getValue(), "Wallet balances must not be negative");
                cfg.set(path(BALANCES_SECTION, entry.getKey()), entry.getValue());
            }
            for (Map.Entry<UUID, Long> entry : snapshot.bankBalances().entrySet()) {
                requireNonNegative(entry.getValue(), "Bank balances must not be negative");
                cfg.set(path(BANK_BALANCES_SECTION, entry.getKey()), entry.getValue());
            }
            for (TransactionRecord transaction : snapshot.walletTransactions()) writeTransaction(transaction);
            snapshot.playerIdentities().values().forEach(this::writePlayerIdentity);
            cfg.set(STORAGE_FORMAT_VERSION_PATH, STORAGE_FORMAT_VERSION);
            cfg.set(LEDGER_BOOTSTRAP_COMPLETED_PATH, true);
        });
    }

    @Override
    public synchronized boolean transferMain(UUID source, UUID target, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.transferMain", YamlStorage.class);
        return transfer(BALANCES_SECTION, source, BALANCES_SECTION, target, amount);
    }

    @Override
    public synchronized boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.transferMainAndBank", YamlStorage.class);
        return mainToBank ? transfer(BALANCES_SECTION, uuid, BANK_BALANCES_SECTION, uuid, amount)
                : transfer(BANK_BALANCES_SECTION, uuid, BALANCES_SECTION, uuid, amount);
    }

    @Override
    public synchronized WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor,
                                                                Instant timestamp) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.ensureWalletAccount", YamlStorage.class);
        requireNonNegative(startingBalanceMinor, "The starting balance must not be negative");
        if (playerId == null) {
            throw new IllegalArgumentException("The player ID must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("The account timestamp must not be null");
        }

        WalletBalance existing = readWalletBalance(playerId);
        if (existing.exists()) {
            if (!existing.valid()) {
                throw new IllegalStateException("The stored wallet balance is invalid for " + playerId);
            }
            return new WalletAccountState(existing.minor(), false);
        }

        TransactionRecord record = TransactionRecords.initialBalance(playerId, startingBalanceMinor, timestamp);
        persistMutation(() -> {
            cfg.set(path(BALANCES_SECTION, playerId), startingBalanceMinor);
            writeTransaction(record);
        });
        return new WalletAccountState(startingBalanceMinor, true);
    }

    @Override
    public synchronized TransactionRecord executeWalletTransaction(WalletTransactionRequest request,
                                                                    long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.executeWalletTransaction", YamlStorage.class);
        if (request == null) {
            throw new IllegalArgumentException("The transaction request must not be null");
        }
        requireNonNegative(maximumBalanceMinor, "The maximum balance must not be negative");

        Optional<TransactionRecord> sameId = findTransactionInternal(request.id());
        if (sameId.isPresent()) {
            return sameId.get();
        }
        Optional<TransactionRecord> idempotentRecord = findIdempotentRecord(request.idempotencyKey());
        if (idempotentRecord.isPresent()) {
            return idempotentRecord.get();
        }

        WalletBalance source = request.sourcePlayerId() == null ? WalletBalance.absent()
                : readWalletBalance(request.sourcePlayerId());
        WalletBalance target = request.targetPlayerId() == null ? WalletBalance.absent()
                : readWalletBalance(request.targetPlayerId());

        if (request.reversalOfTransactionId() != null) {
            Optional<TransactionRecord> original = findTransactionInternal(request.reversalOfTransactionId());
            if (original.isEmpty() || !original.get().isSuccessful()) {
                return persistFailure(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The referenced transaction is not a successful transaction.",
                        source.validMinorOrNull(), target.validMinorOrNull());
            }
            if (hasSuccessfulRollback(request.reversalOfTransactionId())) {
                return persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "A successful rollback already exists for the referenced transaction.",
                        source.validMinorOrNull(), target.validMinorOrNull());
            }
        }

        if ((request.sourcePlayerId() != null && !source.valid())
                || (request.targetPlayerId() != null && !target.valid())) {
            return persistFailure(request, TransactionFailureReason.CONFLICT,
                    "A referenced wallet account contains an invalid stored balance.",
                    source.validMinorOrNull(), target.validMinorOrNull());
        }
        if ((request.sourcePlayerId() != null && !source.exists())
                || (request.targetPlayerId() != null && !target.exists())) {
            return persistFailure(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "A referenced wallet account does not exist.",
                    source.validMinorOrNull(), target.validMinorOrNull());
        }

        return switch (request.operation()) {
            case CREDIT -> executeCredit(request, target.minor(), maximumBalanceMinor);
            case DEBIT -> executeDebit(request, source.minor());
            case TRANSFER -> executeTransfer(request, source.minor(), target.minor(), maximumBalanceMinor);
            case SET -> executeSet(request, target.minor(), maximumBalanceMinor);
        };
    }

    @Override
    public synchronized TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request,
                                                                        boolean mainToBank,
                                                                        long maximumBalanceMinor) throws Exception {
        StorageAccessGuard.requireInternalCaller("YamlStorage.executeWalletBankTransaction", YamlStorage.class);
        if (request == null) {
            throw new IllegalArgumentException("The transaction request must not be null");
        }
        requireNonNegative(maximumBalanceMinor, "The maximum balance must not be negative");
        validateWalletBankRequest(request, mainToBank);

        Optional<TransactionRecord> sameId = findTransactionInternal(request.id());
        if (sameId.isPresent()) {
            return sameId.get();
        }
        Optional<TransactionRecord> idempotentRecord = findIdempotentRecord(request.idempotencyKey());
        if (idempotentRecord.isPresent()) {
            return idempotentRecord.get();
        }

        UUID playerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
        WalletBalance wallet = readWalletBalance(playerId);
        WalletBalance bank = readBankBalance(playerId);
        Long walletMinor = wallet.validMinorOrNull();
        if (!wallet.exists() || !wallet.valid()) {
            return persistFailure(request, wallet.exists() ? TransactionFailureReason.CONFLICT
                            : TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "The wallet account is unavailable for the bank transfer.",
                    mainToBank ? walletMinor : null, mainToBank ? null : walletMinor);
        }
        if (!bank.valid()) {
            return persistFailure(request, TransactionFailureReason.CONFLICT,
                    "The bank account contains an invalid balance.",
                    mainToBank ? walletMinor : null, mainToBank ? null : walletMinor);
        }

        if (request.reversalOfTransactionId() != null) {
            Optional<TransactionRecord> original = findTransactionInternal(request.reversalOfTransactionId());
            if (original.isEmpty() || !original.get().isSuccessful()) {
                return persistFailure(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The referenced transaction is not a successful transaction.",
                        mainToBank ? walletMinor : null, mainToBank ? null : walletMinor);
            }
            if (hasSuccessfulRollback(request.reversalOfTransactionId())) {
                return persistFailure(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "A successful rollback already exists for the referenced transaction.",
                        mainToBank ? walletMinor : null, mainToBank ? null : walletMinor);
            }
        }

        return mainToBank
                ? executeWalletToBank(request, wallet.minor(), bank.minor())
                : executeBankToWallet(request, wallet.minor(), bank.minor(), maximumBalanceMinor);
    }

    @Override
    public synchronized Optional<TransactionRecord> findTransaction(UUID transactionId) {
        if (transactionId == null) {
            return Optional.empty();
        }
        return findTransactionInternal(transactionId);
    }

    @Override
    public synchronized TransactionPage findTransactions(TransactionQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("The transaction query must not be null");
        }

        List<TransactionRecord> matching = getAllWalletTransactionsInternal().stream()
                .filter(record -> matches(query, record))
                .sorted(DESCENDING_TRANSACTION_ORDER)
                .toList();
        int from = Math.min(query.offset(), matching.size());
        int to = Math.min(from + query.limit(), matching.size());
        return new TransactionPage(matching.subList(from, to), query.offset(), query.limit(), to < matching.size());
    }

    @Override
    public synchronized EconomyStatistics getEconomyStatistics() {
        return getEconomyAnalytics().statistics();
    }

    @Override
    public synchronized EconomyAnalytics getEconomyAnalytics() {
        long accountCount = 0;
        long totalBalanceMinor = 0;
        UUID richestPlayerId = null;
        long richestBalanceMinor = 0;

        ConfigurationSection balances = cfg.getConfigurationSection(BALANCES_SECTION);
        if (balances != null) {
            for (String key : balances.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    WalletBalance balance = readWalletBalance(playerId);
                    if (!balance.valid()) {
                        warnMalformedBalance(playerId);
                        continue;
                    }
                    accountCount = Math.addExact(accountCount, 1L);
                    totalBalanceMinor = Math.addExact(totalBalanceMinor, balance.minor());
                    if (richestPlayerId == null || balance.minor() > richestBalanceMinor
                            || (balance.minor() == richestBalanceMinor
                            && playerId.toString().compareTo(richestPlayerId.toString()) < 0)) {
                        richestPlayerId = playerId;
                        richestBalanceMinor = balance.minor();
                    }
                } catch (IllegalArgumentException ignored) {
                    // A malformed account identifier cannot safely participate in economy statistics.
                }
            }
        }

        long totalTransactionCount = 0;
        long successfulTransactionCount = 0;
        long failedTransactionCount = 0;
        long cancelledTransactionCount = 0;
        long totalCreditsMinor = 0;
        long totalDebitsMinor = 0;
        long largestTransactionMinor = 0;
        long totalTransferVolumeMinor = 0;
        for (TransactionRecord record : getAllWalletTransactionsInternal()) {
            totalTransactionCount = Math.addExact(totalTransactionCount, 1L);
            switch (record.status()) {
                case SUCCESS -> {
                    successfulTransactionCount = Math.addExact(successfulTransactionCount, 1L);
                    long[] flows = TransactionRecords.moneySupplyFlows(record);
                    totalCreditsMinor = Math.addExact(totalCreditsMinor, flows[0]);
                    totalDebitsMinor = Math.addExact(totalDebitsMinor, flows[1]);
                    largestTransactionMinor = Math.max(largestTransactionMinor, record.appliedAmountMinor());
                    totalTransferVolumeMinor = Math.addExact(totalTransferVolumeMinor,
                            TransactionRecords.transferVolume(record));
                }
                case FAILED -> failedTransactionCount = Math.addExact(failedTransactionCount, 1L);
                case CANCELLED -> cancelledTransactionCount = Math.addExact(cancelledTransactionCount, 1L);
            }
        }

        EconomyStatistics statistics = new EconomyStatistics(accountCount, totalBalanceMinor, richestPlayerId,
                richestBalanceMinor, totalTransactionCount, successfulTransactionCount, failedTransactionCount,
                cancelledTransactionCount, totalCreditsMinor, totalDebitsMinor);
        return new EconomyAnalytics(statistics, largestTransactionMinor, totalTransferVolumeMinor);
    }

    @Override
    public synchronized PlayerTransactionStatistics getPlayerTransactionStatistics(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("The player ID must not be null");
        }
        return PlayerTransactionStatistics.fromRecords(playerId, getAllWalletTransactionsInternal());
    }

    @Override
    public synchronized List<TransactionRecord> getAllWalletTransactions() {
        return getAllWalletTransactionsInternal().stream().sorted(ASCENDING_TRANSACTION_ORDER).toList();
    }

    private TransactionRecord executeCredit(WalletTransactionRequest request, long targetBalanceMinor,
                                            long maximumBalanceMinor) throws Exception {
        Long newTarget = addExact(targetBalanceMinor, request.amountMinor());
        if (newTarget == null) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting wallet balance cannot be represented.", null, targetBalanceMinor);
        }
        if (exceedsMaximum(newTarget, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", null, targetBalanceMinor);
        }
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                null, null, targetBalanceMinor, newTarget);
        persistMutation(() -> {
            writeWalletBalance(request.targetPlayerId(), newTarget);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord executeDebit(WalletTransactionRequest request, long sourceBalanceMinor) throws Exception {
        if (sourceBalanceMinor < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient.", sourceBalanceMinor, null);
        }
        long newSource = sourceBalanceMinor - request.amountMinor();
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                sourceBalanceMinor, newSource, null, null);
        persistMutation(() -> {
            writeWalletBalance(request.sourcePlayerId(), newSource);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord executeTransfer(WalletTransactionRequest request, long sourceBalanceMinor,
                                              long targetBalanceMinor, long maximumBalanceMinor) throws Exception {
        if (sourceBalanceMinor < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The source wallet balance is insufficient.", sourceBalanceMinor, targetBalanceMinor);
        }
        Long newTarget = addExact(targetBalanceMinor, request.amountMinor());
        if (newTarget == null) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting wallet balance cannot be represented.", sourceBalanceMinor, targetBalanceMinor);
        }
        if (exceedsMaximum(newTarget, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", sourceBalanceMinor, targetBalanceMinor);
        }
        long newSource = sourceBalanceMinor - request.amountMinor();
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(),
                sourceBalanceMinor, newSource, targetBalanceMinor, newTarget);
        persistMutation(() -> {
            writeWalletBalance(request.sourcePlayerId(), newSource);
            writeWalletBalance(request.targetPlayerId(), newTarget);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord executeSet(WalletTransactionRequest request, long targetBalanceMinor,
                                         long maximumBalanceMinor) throws Exception {
        long newTarget = request.targetBalanceMinor();
        if (exceedsMaximum(newTarget, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The target wallet balance would exceed the configured maximum.", null, targetBalanceMinor);
        }
        long appliedAmount = absoluteDifference(targetBalanceMinor, newTarget);
        TransactionRecord record = TransactionRecords.successful(request, appliedAmount,
                null, null, targetBalanceMinor, newTarget);
        persistMutation(() -> {
            writeWalletBalance(request.targetPlayerId(), newTarget);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord executeWalletToBank(WalletTransactionRequest request, long walletBalanceMinor,
                                                  long bankBalanceMinor) throws Exception {
        if (walletBalanceMinor < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The wallet account has insufficient funds for the bank deposit.", walletBalanceMinor, null);
        }
        Long newBankBalance = addExact(bankBalanceMinor, request.amountMinor());
        if (newBankBalance == null) {
            return persistFailure(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The resulting bank balance cannot be represented.", walletBalanceMinor, null);
        }
        long newWalletBalance = walletBalanceMinor - request.amountMinor();
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), walletBalanceMinor,
                newWalletBalance, null, null);
        persistMutation(() -> {
            writeWalletBalance(request.sourcePlayerId(), newWalletBalance);
            writeBankBalance(request.sourcePlayerId(), newBankBalance);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord executeBankToWallet(WalletTransactionRequest request, long walletBalanceMinor,
                                                  long bankBalanceMinor, long maximumBalanceMinor) throws Exception {
        if (bankBalanceMinor < request.amountMinor()) {
            return persistFailure(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                    "The bank account has insufficient funds for the withdrawal.", null, walletBalanceMinor);
        }
        Long newWalletBalance = addExact(walletBalanceMinor, request.amountMinor());
        if (newWalletBalance == null || exceedsMaximum(newWalletBalance, maximumBalanceMinor)) {
            return persistFailure(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                    "The wallet balance would exceed the configured maximum.", null, walletBalanceMinor);
        }
        long newBankBalance = bankBalanceMinor - request.amountMinor();
        TransactionRecord record = TransactionRecords.successful(request, request.amountMinor(), null, null,
                walletBalanceMinor, newWalletBalance);
        persistMutation(() -> {
            writeBankBalance(request.targetPlayerId(), newBankBalance);
            writeWalletBalance(request.targetPlayerId(), newWalletBalance);
            writeTransaction(record);
        });
        return record;
    }

    private TransactionRecord persistFailure(WalletTransactionRequest request, TransactionFailureReason reason,
                                             String detail, Long sourceBalanceMinor, Long targetBalanceMinor)
            throws Exception {
        TransactionRecord record = TransactionRecords.failed(request, reason, detail, sourceBalanceMinor, targetBalanceMinor);
        persistMutation(() -> writeTransaction(record));
        return record;
    }

    @Override
    public boolean hasSuccessfulRollback(UUID originalTransactionId) {
        return getAllWalletTransactionsInternal().stream()
                .anyMatch(record -> record.status() == TransactionStatus.SUCCESS
                        && originalTransactionId.equals(record.reversalOfTransactionId()));
    }

    private Optional<TransactionRecord> findIdempotentRecord(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return getAllWalletTransactionsInternal().stream()
                .filter(record -> idempotencyKey.equals(record.idempotencyKey()))
                .findFirst();
    }

    private Optional<TransactionRecord> findTransactionInternal(UUID transactionId) {
        if (!cfg.contains(transactionPath(transactionId))) {
            return Optional.empty();
        }
        return readTransaction(transactionId);
    }

    private List<TransactionRecord> getAllWalletTransactionsInternal() {
        ConfigurationSection transactions = cfg.getConfigurationSection(WALLET_TRANSACTIONS_SECTION);
        if (transactions == null) {
            return List.of();
        }
        List<TransactionRecord> records = new ArrayList<>();
        for (String key : transactions.getKeys(false)) {
            try {
                UUID transactionId = UUID.fromString(key);
                readTransaction(transactionId).ifPresent(records::add);
            } catch (IllegalArgumentException exception) {
                warnMalformedTransaction(key, "The transaction identifier is not a UUID.");
            }
        }
        return records;
    }

    private Optional<TransactionRecord> readTransaction(UUID transactionId) {
        String base = transactionPath(transactionId);
        try {
            UUID storedId = optionalUuid(base + ".id");
            if (storedId != null && !storedId.equals(transactionId)) {
                throw new IllegalArgumentException("The stored transaction ID does not match its journal key.");
            }
            Instant timestamp = readInstant(base + ".timestamp");
            TransactionType type = readEnum(base + ".type", TransactionType.class, null);
            TransactionStatus status = readEnum(base + ".status", TransactionStatus.class, null);
            TransactionOriginType originType = readEnum(base + ".origin.type", TransactionOriginType.class, null);
            UUID originPlayerId = optionalUuid(base + ".origin.player_id");
            String originIdentifier = requiredString(base + ".origin.identifier");
            WalletOperation operation = readEnum(base + ".operation", WalletOperation.class, null);
            UUID sourcePlayerId = optionalUuid(base + ".source_player_id");
            UUID targetPlayerId = optionalUuid(base + ".target_player_id");
            long requestedAmountMinor = requiredLong(base + ".requested_amount_minor");
            long appliedAmountMinor = requiredLong(base + ".applied_amount_minor");
            Long sourceBeforeMinor = optionalLong(base + ".source_balance_before_minor");
            Long sourceAfterMinor = optionalLong(base + ".source_balance_after_minor");
            Long targetBeforeMinor = optionalLong(base + ".target_balance_before_minor");
            Long targetAfterMinor = optionalLong(base + ".target_balance_after_minor");
            String reason = requiredString(base + ".reason");
            UUID reversalOfTransactionId = optionalUuid(base + ".reversal_of_transaction_id");
            UUID batchId = optionalUuid(base + ".batch_id");
            String idempotencyKey = optionalString(base + ".idempotency_key");
            TransactionFailureReason failureReason = readEnum(base + ".failure_reason",
                    TransactionFailureReason.class, status == TransactionStatus.SUCCESS
                            ? TransactionFailureReason.NONE : TransactionFailureReason.STORAGE_FAILURE);
            String failureDetail = optionalString(base + ".failure_detail");

            return Optional.of(new TransactionRecord(transactionId, timestamp, type, status,
                    new TransactionOrigin(originType, originPlayerId, originIdentifier), operation,
                    sourcePlayerId, targetPlayerId, requestedAmountMinor, appliedAmountMinor,
                    sourceBeforeMinor, sourceAfterMinor, targetBeforeMinor, targetAfterMinor,
                    reason, readMetadata(base + ".metadata"), reversalOfTransactionId, batchId,
                    idempotencyKey, failureReason, failureDetail));
        } catch (Exception exception) {
            warnMalformedTransaction(transactionId.toString(), exception.getMessage());
            return Optional.empty();
        }
    }

    private void writeTransaction(TransactionRecord record) {
        String base = transactionPath(record.id());
        cfg.set(base, null);
        cfg.set(base + ".id", record.id().toString());
        cfg.set(base + ".timestamp", record.timestamp().toString());
        cfg.set(base + ".type", record.type().name());
        cfg.set(base + ".status", record.status().name());
        cfg.set(base + ".origin.type", record.origin().type().name());
        if (record.origin().playerId() != null) {
            cfg.set(base + ".origin.player_id", record.origin().playerId().toString());
        }
        cfg.set(base + ".origin.identifier", record.origin().identifier());
        cfg.set(base + ".operation", record.operation().name());
        writeOptionalUuid(base + ".source_player_id", record.sourcePlayerId());
        writeOptionalUuid(base + ".target_player_id", record.targetPlayerId());
        cfg.set(base + ".requested_amount_minor", record.requestedAmountMinor());
        cfg.set(base + ".applied_amount_minor", record.appliedAmountMinor());
        writeOptionalLong(base + ".source_balance_before_minor", record.sourceBalanceBeforeMinor());
        writeOptionalLong(base + ".source_balance_after_minor", record.sourceBalanceAfterMinor());
        writeOptionalLong(base + ".target_balance_before_minor", record.targetBalanceBeforeMinor());
        writeOptionalLong(base + ".target_balance_after_minor", record.targetBalanceAfterMinor());
        cfg.set(base + ".reason", record.reason());
        cfg.set(base + ".metadata", serializeMetadata(record.metadata()));
        writeOptionalUuid(base + ".reversal_of_transaction_id", record.reversalOfTransactionId());
        writeOptionalUuid(base + ".batch_id", record.batchId());
        cfg.set(base + ".idempotency_key", record.idempotencyKey());
        cfg.set(base + ".failure_reason", record.failureReason().name());
        cfg.set(base + ".failure_detail", record.failureDetail());
    }

    private List<Map<String, String>> serializeMetadata(Map<String, String> metadata) {
        List<Map<String, String>> serialized = new ArrayList<>(metadata.size());
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            Map<String, String> value = new LinkedHashMap<>();
            value.put("key", entry.getKey());
            value.put("value", entry.getValue());
            serialized.add(value);
        }
        return serialized;
    }

    private Map<String, String> readMetadata(String path) {
        Object raw = cfg.get(path);
        if (raw == null) {
            return Map.of();
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (raw instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> value) || value.get("key") == null || value.get("value") == null) {
                    throw new IllegalArgumentException("The transaction metadata entry is invalid.");
                }
                metadata.put(String.valueOf(value.get("key")), String.valueOf(value.get("value")));
            }
            return metadata;
        }

        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("The transaction metadata is invalid.");
        }
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value == null) {
                throw new IllegalArgumentException("The transaction metadata value is invalid.");
            }
            metadata.put(key, value);
        }
        return metadata;
    }

    private boolean matches(TransactionQuery query, TransactionRecord record) {
        if (query.transactionId() != null && !query.transactionId().equals(record.id())) {
            return false;
        }
        if (query.playerId() != null && !query.playerId().equals(record.sourcePlayerId())
                && !query.playerId().equals(record.targetPlayerId())) {
            return false;
        }
        if (query.sourcePlayerId() != null && !query.sourcePlayerId().equals(record.sourcePlayerId())) {
            return false;
        }
        if (query.targetPlayerId() != null && !query.targetPlayerId().equals(record.targetPlayerId())) {
            return false;
        }
        if (query.type() != null && query.type() != record.type()) {
            return false;
        }
        if (query.status() != null && query.status() != record.status()) {
            return false;
        }
        if (query.fromInclusive() != null && record.timestamp().isBefore(query.fromInclusive())) {
            return false;
        }
        return query.toExclusive() == null || record.timestamp().isBefore(query.toExclusive());
    }

    private void upgradeWalletLedgerFormat() throws Exception {
        YamlConfiguration previous = copyConfiguration();
        boolean changed = false;
        int version = cfg.getInt(STORAGE_FORMAT_VERSION_PATH, 0);
        if (version > STORAGE_FORMAT_VERSION) {
            throw new IllegalStateException("YAML storage format " + version + " is newer than supported format "
                    + STORAGE_FORMAT_VERSION);
        }
        if (version < STORAGE_FORMAT_VERSION) {
            migrateLegacyBalancesToMinorUnits();
            cfg.set(STORAGE_FORMAT_VERSION_PATH, STORAGE_FORMAT_VERSION);
            changed = true;
        }

        if (!cfg.getBoolean(LEDGER_BOOTSTRAP_COMPLETED_PATH, false)) {
            if (!hasJournalData()) {
                Instant timestamp = Instant.now();
                for (Map.Entry<UUID, Double> entry : readAll(BALANCES_SECTION).entrySet()) {
                    if (!Money.isPositive(entry.getValue())) {
                        continue;
                    }
                    try {
                        long amountMinor = Money.toMinorUnits(entry.getValue());
                        writeTransaction(TransactionRecords.legacyOpeningBalance(entry.getKey(), amountMinor, timestamp));
                    } catch (IllegalArgumentException exception) {
                        warnMalformedBalance(entry.getKey());
                    }
                }
            }
            cfg.set(LEDGER_BOOTSTRAP_COMPLETED_PATH, true);
            changed = true;
        }

        if (changed) {
            try {
                persist();
            } catch (Exception exception) {
                cfg = previous;
                throw exception;
            }
        }
    }

    /**
     * Creates canonical exact balance sections from the v1/v2 major-unit data. The surrounding
     * temporary-file replacement makes the YAML migration all-or-nothing; legacy values are kept
     * as read-only upgrade evidence and are never used by the v3 runtime.
     */
    private void migrateLegacyBalancesToMinorUnits() {
        migrateLegacyBalanceSection(LEGACY_BALANCES_SECTION, BALANCES_SECTION);
        migrateLegacyBalanceSection(LEGACY_BANK_BALANCES_SECTION, BANK_BALANCES_SECTION);
    }

    private void migrateLegacyBalanceSection(String legacySection, String minorSection) {
        ConfigurationSection legacy = cfg.getConfigurationSection(legacySection);
        if (legacy == null) {
            return;
        }
        for (String key : legacy.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid legacy account identifier in " + legacySection + ": " + key,
                        exception);
            }
            String legacyPath = path(legacySection, playerId);
            long converted;
            try {
                converted = Money.parseMinorUnits(String.valueOf(cfg.get(legacyPath)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid legacy balance for " + playerId + " in " + legacySection,
                        exception);
            }
            if (converted < 0L) {
                throw new IllegalStateException("Negative legacy balance for " + playerId + " in " + legacySection);
            }
            String minorPath = path(minorSection, playerId);
            if (cfg.contains(minorPath)) {
                long existing = parseLong(cfg.get(minorPath), minorPath);
                if (existing != converted) {
                    throw new IllegalStateException("Conflicting canonical and legacy balances for " + playerId);
                }
            } else {
                cfg.set(minorPath, converted);
            }
        }
    }

    private boolean hasJournalData() {
        if (!cfg.contains(WALLET_TRANSACTIONS_SECTION)) {
            return false;
        }
        ConfigurationSection transactions = cfg.getConfigurationSection(WALLET_TRANSACTIONS_SECTION);
        return transactions == null || !transactions.getKeys(false).isEmpty();
    }

    private boolean transfer(String fromSection, UUID source, String toSection, UUID target, double amount) throws Exception {
        if (!Money.isPositive(amount)) {
            return false;
        }
        if (fromSection.equals(toSection) && source.equals(target)) {
            return false;
        }
        long amountMinor = Money.toMinorUnits(amount);
        WalletBalance from = readNonNegativeBalance(fromSection, source);
        if (!from.exists() || !from.valid() || from.minor() < amountMinor) {
            return false;
        }
        WalletBalance targetBalance = readNonNegativeBalance(toSection, target);
        if (targetBalance.exists() && !targetBalance.valid()) {
            throw new IllegalStateException("Invalid stored balance");
        }
        Long targetAfter = addExact(targetBalance.minor(), amountMinor);
        if (targetAfter == null) {
            throw new IllegalStateException("The resulting balance cannot be represented");
        }

        persistMutation(() -> {
            cfg.set(path(fromSection, source), Math.subtractExact(from.minor(), amountMinor));
            cfg.set(path(toSection, target), targetAfter);
        });
        return true;
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

    private WalletBalance readWalletBalance(UUID playerId) {
        return readNonNegativeBalance(BALANCES_SECTION, playerId);
    }

    private WalletBalance readBankBalance(UUID playerId) {
        return readNonNegativeBalance(BANK_BALANCES_SECTION, playerId);
    }

    private WalletBalance readNonNegativeBalance(String section, UUID playerId) {
        String key = path(section, playerId);
        if (!cfg.contains(key)) {
            return WalletBalance.absent();
        }
        try {
            long value = parseLong(cfg.get(key), key);
            return value < 0L ? WalletBalance.invalid() : WalletBalance.present(value);
        } catch (IllegalArgumentException exception) {
            return WalletBalance.invalid();
        }
    }

    private void writeWalletBalance(UUID playerId, long balanceMinor) {
        requireNonNegative(balanceMinor, "Wallet balances must not be negative");
        cfg.set(path(BALANCES_SECTION, playerId), balanceMinor);
    }

    private void writeBankBalance(UUID playerId, long balanceMinor) {
        requireNonNegative(balanceMinor, "Bank balances must not be negative");
        cfg.set(path(BANK_BALANCES_SECTION, playerId), balanceMinor);
    }

    private static Long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static long absoluteDifference(long first, long second) {
        try {
            return Math.abs(Math.subtractExact(first, second));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The balance difference cannot be represented", exception);
        }
    }

    private static boolean exceedsMaximum(long balanceMinor, long maximumBalanceMinor) {
        return maximumBalanceMinor > 0 && balanceMinor > maximumBalanceMinor;
    }

    private static void requireNonNegative(long value, String message) {
        if (value < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private Double getNullable(String section, UUID uuid) {
        WalletBalance balance = readNonNegativeBalance(section, uuid);
        return balance.exists() && balance.valid() ? Money.fromMinorUnits(balance.minor()) : null;
    }

    private Map<UUID, Double> readAll(String section) {
        Map<UUID, Double> result = new HashMap<>();
        ConfigurationSection configurationSection = cfg.getConfigurationSection(section);
        if (configurationSection == null) {
            return result;
        }
        for (String key : configurationSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Double value = getNullable(section, uuid);
                if (value != null) {
                    result.put(uuid, value);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed legacy account identifiers.
            }
        }
        return result;
    }

    private Map<UUID, Long> readAllMinor(String section) {
        Map<UUID, Long> result = new HashMap<>();
        ConfigurationSection configurationSection = cfg.getConfigurationSection(section);
        if (configurationSection == null) {
            return result;
        }
        for (String key : configurationSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                WalletBalance value = readNonNegativeBalance(section, playerId);
                if (!value.valid()) {
                    throw new IllegalStateException("Invalid canonical balance for " + playerId);
                }
                result.put(playerId, value.minor());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid account identifier in canonical YAML balances: " + key,
                        exception);
            }
        }
        return Map.copyOf(result);
    }

    private String path(String section, UUID uuid) {
        return section + "." + uuid;
    }

    private String transactionPath(UUID transactionId) {
        return WALLET_TRANSACTIONS_SECTION + "." + transactionId;
    }

    private PlayerIdentity readPlayerIdentity(UUID playerId) {
        String base = path(PLAYER_IDENTITIES_SECTION, playerId);
        if (!cfg.isConfigurationSection(base)) return null;
        String name = requiredString(base + ".last_known_name");
        String normalized = requiredString(base + ".normalized_name");
        long lastLogin = requiredLong(base + ".last_login_epoch_millis");
        PlayerType type = PlayerType.valueOf(requiredString(base + ".player_type"));
        return new PlayerIdentity(playerId, name, normalized, Instant.ofEpochMilli(lastLogin), type);
    }

    private void writePlayerIdentity(PlayerIdentity identity) {
        String base = path(PLAYER_IDENTITIES_SECTION, identity.playerId());
        cfg.set(base + ".last_known_name", identity.lastKnownName());
        cfg.set(base + ".normalized_name", identity.normalizedName());
        cfg.set(base + ".last_login_epoch_millis", identity.lastLoginAt().toEpochMilli());
        cfg.set(base + ".player_type", identity.playerType().name());
    }


    private void putAndPersist(String key, long value) throws Exception {
        persistMutation(() -> cfg.set(key, value));
    }

    private void persistMutation(StorageMutation mutation) throws Exception {
        YamlConfiguration previous = copyConfiguration();
        try {
            mutation.apply();
            persist();
        } catch (Exception exception) {
            cfg = previous;
            throw exception;
        }
    }

    private YamlConfiguration copyConfiguration() throws Exception {
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(cfg.saveToString());
        return copy;
    }

    /** Writes a complete replacement file, preserving the last valid file if serialization fails. */
    private void persist() throws Exception {
        Path temporary = new File(file.getParentFile(), file.getName() + ".tmp").toPath();
        try {
            cfg.save(temporary.toFile());
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private UUID optionalUuid(String path) {
        String value = optionalString(path);
        return value == null ? null : UUID.fromString(value);
    }

    private void writeOptionalUuid(String path, UUID value) {
        cfg.set(path, value == null ? null : value.toString());
    }

    private Long optionalLong(String path) {
        if (!cfg.contains(path)) {
            return null;
        }
        return parseLong(cfg.get(path), path);
    }

    private void writeOptionalLong(String path, Long value) {
        cfg.set(path, value);
    }

    private long requiredLong(String path) {
        if (!cfg.contains(path)) {
            throw new IllegalArgumentException("Missing required transaction value: " + path);
        }
        return parseLong(cfg.get(path), path);
    }

    private long parseLong(Object raw, String path) {
        if (raw instanceof Number number) {
            if (number instanceof Float || number instanceof Double) {
                double value = number.doubleValue();
                if (!Double.isFinite(value) || Math.rint(value) != value) {
                    throw new IllegalArgumentException("The transaction value is not an integer: " + path);
                }
            }
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The transaction value is not a long: " + path, exception);
        }
    }

    private String requiredString(String path) {
        String value = optionalString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required transaction value: " + path);
        }
        return value;
    }

    private String optionalString(String path) {
        if (!cfg.contains(path)) {
            return null;
        }
        Object value = cfg.get(path);
        return value == null ? null : String.valueOf(value);
    }

    private Instant readInstant(String path) {
        Object raw = cfg.get(path);
        if (raw == null) {
            throw new IllegalArgumentException("Missing required transaction timestamp");
        }
        try {
            return Instant.parse(String.valueOf(raw));
        } catch (Exception ignored) {
            try {
                return Instant.ofEpochMilli(parseLong(raw, path));
            } catch (Exception exception) {
                throw new IllegalArgumentException("Invalid transaction timestamp: " + path, exception);
            }
        }
    }

    private <E extends Enum<E>> E readEnum(String path, Class<E> type, E defaultValue) {
        String value = optionalString(path);
        if (value == null || value.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new IllegalArgumentException("Missing required transaction value: " + path);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid transaction enum value: " + path, exception);
        }
    }

    private void warnMalformedTransaction(String identifier, String detail) {
        if (plugin == null || !malformedTransactionWarnings.add(identifier)) {
            return;
        }
        plugin.getLogger().warning("Ignoring malformed wallet transaction '" + identifier + "': "
                + (detail == null ? "invalid data" : detail));
    }

    private void warnMalformedBalance(UUID playerId) {
        if (plugin != null) {
            plugin.getLogger().warning("Ignoring invalid wallet balance for " + playerId + " in YAML storage.");
        }
    }

    @Override
    public void close() {
        // YAML storage has no open external resources.
    }

    @Override
    public boolean isDebug() {
        return debug;
    }

    @FunctionalInterface
    private interface StorageMutation {
        void apply() throws Exception;
    }

    private record WalletBalance(boolean exists, boolean valid, long minor) {
        private static WalletBalance absent() {
            return new WalletBalance(false, true, 0L);
        }

        private static WalletBalance invalid() {
            return new WalletBalance(true, false, 0L);
        }

        private static WalletBalance present(long minor) {
            return new WalletBalance(true, true, minor);
        }

        private Long validMinorOrNull() {
            return exists && valid ? minor : null;
        }
    }
}
