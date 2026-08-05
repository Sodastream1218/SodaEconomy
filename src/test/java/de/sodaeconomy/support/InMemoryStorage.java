package de.sodaeconomy.support;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.storage.Storage;
import de.sodaeconomy.storage.StorageSnapshot;
import de.sodaeconomy.storage.WalletTransactionStore;
import de.sodaeconomy.transaction.EconomyStatistics;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionPage;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionRecords;
import de.sodaeconomy.transaction.TransactionStatus;
import de.sodaeconomy.transaction.WalletAccountState;
import de.sodaeconomy.transaction.WalletOperation;
import de.sodaeconomy.transaction.WalletTransactionRequest;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Deterministic transaction-capable storage implementation for service tests. */
public final class InMemoryStorage implements Storage, WalletTransactionStore {
    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, Double> bankBalances = new HashMap<>();
    private final Map<UUID, TransactionRecord> transactions = new LinkedHashMap<>();
    private final Map<UUID, PlayerIdentity> playerIdentities = new HashMap<>();
    private int accountCreationAttempts;

    @Override public void init(JavaPlugin plugin) { }
    @Override public synchronized Double getBalance(UUID uuid) { return balances.get(uuid); }
    @Override public synchronized double getOrCreateBalance(UUID uuid, double initialBalance) {
        accountCreationAttempts++;
        double balance = balances.computeIfAbsent(uuid, ignored -> Money.normalize(initialBalance));
        bankBalances.putIfAbsent(uuid, 0D);
        return balance;
    }
    @Override public synchronized void setBalance(UUID uuid, double amount) { balances.put(uuid, Money.normalize(amount)); }
    @Override public synchronized Map<UUID, Double> getAllBalances() { return Map.copyOf(balances); }
    @Override public synchronized void saveAll(Map<UUID, Double> values) { values.forEach(this::setBalance); }
    @Override public synchronized Map<UUID, PlayerIdentity> getAllPlayerIdentities() {
        return Map.copyOf(playerIdentities);
    }
    @Override public synchronized Optional<PlayerIdentity> findPlayerIdentity(UUID playerId) {
        return Optional.ofNullable(playerIdentities.get(playerId));
    }
    @Override public synchronized List<PlayerIdentity> findPlayerIdentitiesByNormalizedName(String normalizedName) {
        return playerIdentities.values().stream()
                .filter(identity -> identity.normalizedName().equals(normalizedName)).toList();
    }
    @Override public synchronized Map<UUID, PlayerIdentity> findPlayerIdentities(Set<UUID> playerIds) {
        Map<UUID, PlayerIdentity> result = new HashMap<>();
        for (UUID playerId : playerIds) {
            PlayerIdentity identity = playerIdentities.get(playerId);
            if (identity != null) result.put(playerId, identity);
        }
        return Map.copyOf(result);
    }
    @Override public synchronized void upsertPlayerIdentity(PlayerIdentity identity) {
        playerIdentities.merge(identity.playerId(), identity, PlayerIdentity::merge);
    }
    @Override public synchronized void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) {
        playerIdentities.clear();
        playerIdentities.putAll(identities);
    }

    @Override public synchronized Double getBankBalance(UUID uuid) { return bankBalances.get(uuid); }
    @Override public synchronized void setBankBalance(UUID uuid, double amount) { bankBalances.put(uuid, Money.normalize(amount)); }
    @Override public synchronized Map<UUID, Double> getAllBankBalances() { return Map.copyOf(bankBalances); }
    @Override public synchronized void saveAllBank(Map<UUID, Double> values) { values.forEach(this::setBankBalance); }

    @Override
    public synchronized void replaceSnapshot(StorageSnapshot snapshot) {
        Map<UUID, Double> previousBalances = new HashMap<>(balances);
        Map<UUID, Double> previousBankBalances = new HashMap<>(bankBalances);
        Map<UUID, TransactionRecord> previousTransactions = new LinkedHashMap<>(transactions);
        try {
            balances.clear();
            bankBalances.clear();
            transactions.clear();
            snapshot.balances().forEach(this::setBalance);
            snapshot.bankBalances().forEach(this::setBankBalance);
            snapshot.walletTransactions().forEach(record -> transactions.put(record.id(), record));
        } catch (RuntimeException exception) {
            balances.clear(); balances.putAll(previousBalances);
            bankBalances.clear(); bankBalances.putAll(previousBankBalances);
            transactions.clear(); transactions.putAll(previousTransactions);
            throw exception;
        }
    }

    @Override public synchronized boolean transferMain(UUID source, UUID target, double amount) {
        return transfer(balances, source, balances, target, amount);
    }

    @Override public synchronized boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) {
        return mainToBank ? transfer(balances, uuid, bankBalances, uuid, amount)
                : transfer(bankBalances, uuid, balances, uuid, amount);
    }

    @Override
    public synchronized WalletAccountState ensureWalletAccount(UUID playerId, long startingBalanceMinor, Instant timestamp) {
        Double existing = balances.get(playerId);
        if (existing != null) return new WalletAccountState(Money.toMinorUnits(existing), false);
        double balance = Money.fromMinorUnits(startingBalanceMinor);
        balances.put(playerId, balance);
        bankBalances.putIfAbsent(playerId, 0D);
        TransactionRecord initial = TransactionRecords.initialBalance(playerId, startingBalanceMinor, timestamp);
        transactions.put(initial.id(), initial);
        return new WalletAccountState(startingBalanceMinor, true);
    }

    @Override
    public synchronized TransactionRecord executeWalletTransaction(WalletTransactionRequest request, long maximumBalanceMinor) {
        TransactionRecord sameId = transactions.get(request.id());
        if (sameId != null) {
            return sameId;
        }
        if (request.idempotencyKey() != null) {
            for (TransactionRecord record : transactions.values()) {
                if (request.idempotencyKey().equals(record.idempotencyKey())) return record;
            }
        }
        if (request.reversalOfTransactionId() != null) {
            TransactionRecord original = transactions.get(request.reversalOfTransactionId());
            if (original == null || !original.isSuccessful()) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The original transaction is not successful.", null, null));
            }
            if (transactions.values().stream().anyMatch(record -> record.isSuccessful()
                    && request.reversalOfTransactionId().equals(record.reversalOfTransactionId()))) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The original transaction already has a successful rollback.", null, null));
            }
        }
        Long source = request.sourcePlayerId() == null ? null : minorBalance(request.sourcePlayerId());
        Long target = request.targetPlayerId() == null ? null : minorBalance(request.targetPlayerId());
        if ((request.sourcePlayerId() != null && source == null) || (request.targetPlayerId() != null && target == null)) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "A wallet account is missing.", source, target));
        }
        try {
            return switch (request.operation()) {
                case CREDIT -> credit(request, target, maximumBalanceMinor);
                case DEBIT -> debit(request, source);
                case TRANSFER -> transfer(request, source, target, maximumBalanceMinor);
                case SET -> set(request, target, maximumBalanceMinor);
            };
        } catch (ArithmeticException exception) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The requested amount is out of range.", source, target));
        }
    }

    @Override
    public synchronized TransactionRecord executeWalletBankTransaction(WalletTransactionRequest request, boolean mainToBank,
                                                                        long maximumBalanceMinor) {
        TransactionRecord sameId = transactions.get(request.id());
        if (sameId != null) {
            return sameId;
        }
        if (request.idempotencyKey() != null) {
            for (TransactionRecord record : transactions.values()) {
                if (request.idempotencyKey().equals(record.idempotencyKey())) {
                    return record;
                }
            }
        }
        if (request.reversalOfTransactionId() != null) {
            TransactionRecord original = transactions.get(request.reversalOfTransactionId());
            if (original == null || !original.isSuccessful()) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.NOT_REVERSIBLE,
                        "The original transaction is not successful.", null, null));
            }
            if (transactions.values().stream().anyMatch(record -> record.isSuccessful()
                    && request.reversalOfTransactionId().equals(record.reversalOfTransactionId()))) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.DUPLICATE_ROLLBACK,
                        "The original transaction already has a successful rollback.", null, null));
            }
        }
        if (mainToBank && request.operation() != WalletOperation.DEBIT) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.INVALID_REQUEST,
                    "A wallet-to-bank transaction must debit the wallet.", null, null));
        }
        if (!mainToBank && request.operation() != WalletOperation.CREDIT) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.INVALID_REQUEST,
                    "A bank-to-wallet transaction must credit the wallet.", null, null));
        }

        UUID playerId = mainToBank ? request.sourcePlayerId() : request.targetPlayerId();
        Long walletBalance = playerId == null ? null : minorBalance(playerId);
        Double bankBalance = playerId == null ? null : bankBalances.get(playerId);
        if (walletBalance == null || bankBalance == null) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.ACCOUNT_NOT_FOUND,
                    "A wallet or bank account is missing.", mainToBank ? walletBalance : null,
                    mainToBank ? null : walletBalance));
        }

        try {
            long bankBalanceMinor = Money.toMinorUnits(bankBalance);
            if (mainToBank) {
                if (walletBalance < request.amountMinor()) {
                    return append(TransactionRecords.failed(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                            "The wallet account has insufficient funds.", walletBalance, null));
                }
                long walletAfter = walletBalance - request.amountMinor();
                long bankAfter = Math.addExact(bankBalanceMinor, request.amountMinor());
                balances.put(playerId, Money.fromMinorUnits(walletAfter));
                bankBalances.put(playerId, Money.fromMinorUnits(bankAfter));
                return append(TransactionRecords.successful(request, request.amountMinor(), walletBalance, walletAfter,
                        null, null));
            }

            if (bankBalanceMinor < request.amountMinor()) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.INSUFFICIENT_FUNDS,
                        "The bank account has insufficient funds.", null, walletBalance));
            }
            long walletAfter = Math.addExact(walletBalance, request.amountMinor());
            if (maximumBalanceMinor > 0L && walletAfter > maximumBalanceMinor) {
                return append(TransactionRecords.failed(request, TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED,
                        "The wallet maximum would be exceeded.", null, walletBalance));
            }
            long bankAfter = bankBalanceMinor - request.amountMinor();
            balances.put(playerId, Money.fromMinorUnits(walletAfter));
            bankBalances.put(playerId, Money.fromMinorUnits(bankAfter));
            return append(TransactionRecords.successful(request, request.amountMinor(), null, null,
                    walletBalance, walletAfter));
        } catch (ArithmeticException exception) {
            return append(TransactionRecords.failed(request, TransactionFailureReason.INVALID_AMOUNT,
                    "The requested amount is out of range.", mainToBank ? walletBalance : null,
                    mainToBank ? null : walletBalance));
        }
    }

    private TransactionRecord credit(WalletTransactionRequest request, long target, long maximum) {
        long after = Math.addExact(target, request.amountMinor());
        if (maximum > 0 && after > maximum) return append(TransactionRecords.failed(request,
                TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED, "The target maximum would be exceeded.", null, target));
        balances.put(request.targetPlayerId(), Money.fromMinorUnits(after));
        return append(TransactionRecords.successful(request, request.amountMinor(), null, null, target, after));
    }

    private TransactionRecord debit(WalletTransactionRequest request, long source) {
        if (source < request.amountMinor()) return append(TransactionRecords.failed(request,
                TransactionFailureReason.INSUFFICIENT_FUNDS, "The source account has insufficient funds.", source, null));
        long after = source - request.amountMinor();
        balances.put(request.sourcePlayerId(), Money.fromMinorUnits(after));
        return append(TransactionRecords.successful(request, request.amountMinor(), source, after, null, null));
    }

    private TransactionRecord transfer(WalletTransactionRequest request, long source, long target, long maximum) {
        if (source < request.amountMinor()) return append(TransactionRecords.failed(request,
                TransactionFailureReason.INSUFFICIENT_FUNDS, "The source account has insufficient funds.", source, target));
        long targetAfter = Math.addExact(target, request.amountMinor());
        if (maximum > 0 && targetAfter > maximum) return append(TransactionRecords.failed(request,
                TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED, "The target maximum would be exceeded.", source, target));
        long sourceAfter = source - request.amountMinor();
        balances.put(request.sourcePlayerId(), Money.fromMinorUnits(sourceAfter));
        balances.put(request.targetPlayerId(), Money.fromMinorUnits(targetAfter));
        return append(TransactionRecords.successful(request, request.amountMinor(), source, sourceAfter, target, targetAfter));
    }

    private TransactionRecord set(WalletTransactionRequest request, long target, long maximum) {
        long after = request.targetBalanceMinor();
        if (maximum > 0 && after > maximum) return append(TransactionRecords.failed(request,
                TransactionFailureReason.MAXIMUM_BALANCE_EXCEEDED, "The target maximum would be exceeded.", null, target));
        balances.put(request.targetPlayerId(), Money.fromMinorUnits(after));
        return append(TransactionRecords.successful(request, Math.abs(after - target), null, null, target, after));
    }

    private TransactionRecord append(TransactionRecord record) {
        transactions.put(record.id(), record);
        return record;
    }

    private Long minorBalance(UUID playerId) {
        Double balance = balances.get(playerId);
        return balance == null ? null : Money.toMinorUnits(balance);
    }

    @Override public synchronized Optional<TransactionRecord> findTransaction(UUID transactionId) {
        return Optional.ofNullable(transactions.get(transactionId));
    }

    @Override
    public synchronized TransactionPage findTransactions(TransactionQuery query) {
        List<TransactionRecord> filtered = transactions.values().stream()
                .filter(record -> matches(record, query))
                .sorted(Comparator.comparing(TransactionRecord::timestamp).reversed()
                        .thenComparing(TransactionRecord::id, Comparator.reverseOrder()))
                .toList();
        int from = Math.min(query.offset(), filtered.size());
        int to = Math.min(from + query.limit(), filtered.size());
        return new TransactionPage(filtered.subList(from, to), query.offset(), query.limit(), to < filtered.size());
    }

    @Override
    public synchronized EconomyStatistics getEconomyStatistics() {
        long totalBalance = balances.values().stream().mapToLong(Money::toMinorUnits).sum();
        Map.Entry<UUID, Double> richest = balances.entrySet().stream()
                .max((left, right) -> {
                    int balanceOrder = Double.compare(left.getValue(), right.getValue());
                    return balanceOrder != 0 ? balanceOrder : right.getKey().toString().compareTo(left.getKey().toString());
                })
                .orElse(null);
        long successes = transactions.values().stream().filter(TransactionRecord::isSuccessful).count();
        long failures = transactions.values().stream().filter(record -> record.status() == TransactionStatus.FAILED).count();
        long cancelled = transactions.values().stream().filter(record -> record.status() == TransactionStatus.CANCELLED).count();
        long credits = 0L;
        long debits = 0L;
        for (TransactionRecord record : transactions.values()) {
            long[] flows = TransactionRecords.moneySupplyFlows(record);
            credits = Math.addExact(credits, flows[0]);
            debits = Math.addExact(debits, flows[1]);
        }
        return new EconomyStatistics(balances.size(), totalBalance, richest == null ? null : richest.getKey(),
                richest == null ? 0L : Money.toMinorUnits(richest.getValue()), transactions.size(), successes,
                failures, cancelled, credits, debits);
    }

    @Override public synchronized List<TransactionRecord> getAllWalletTransactions() { return List.copyOf(transactions.values()); }

    private boolean matches(TransactionRecord record, TransactionQuery query) {
        if (query.transactionId() != null && !query.transactionId().equals(record.id())) return false;
        if (query.playerId() != null && !query.playerId().equals(record.sourcePlayerId())
                && !query.playerId().equals(record.targetPlayerId())) return false;
        if (query.sourcePlayerId() != null && !query.sourcePlayerId().equals(record.sourcePlayerId())) return false;
        if (query.targetPlayerId() != null && !query.targetPlayerId().equals(record.targetPlayerId())) return false;
        if (query.type() != null && query.type() != record.type()) return false;
        if (query.status() != null && query.status() != record.status()) return false;
        if (query.fromInclusive() != null && record.timestamp().isBefore(query.fromInclusive())) return false;
        return query.toExclusive() == null || record.timestamp().isBefore(query.toExclusive());
    }

    private boolean transfer(Map<UUID, Double> sourceBalances, UUID source, Map<UUID, Double> targetBalances, UUID target, double amount) {
        if (!Money.isPositive(amount) || (sourceBalances == targetBalances && source.equals(target))) return false;
        double value = Money.normalize(amount);
        Double sourceBalance = sourceBalances.get(source);
        Double targetBalance = targetBalances.get(target);
        if (sourceBalance == null || targetBalance == null || sourceBalance < value) return false;
        sourceBalances.put(source, Money.normalize(sourceBalance - value));
        targetBalances.put(target, Money.normalize(targetBalance + value));
        return true;
    }

    public synchronized int accountCreationAttempts() { return accountCreationAttempts; }
    @Override public void close() { }
    @Override public boolean isDebug() { return false; }
}
