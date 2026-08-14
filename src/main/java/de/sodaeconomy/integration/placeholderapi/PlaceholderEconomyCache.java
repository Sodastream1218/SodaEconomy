package de.sodaeconomy.integration.placeholderapi;

import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.leaderboard.LeaderboardRanking;
import de.sodaeconomy.transaction.EconomyTransactionApi;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionType;
import de.sodaeconomy.transaction.WalletTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Asynchronously refreshed presentation cache. Placeholder callbacks never wait for persistence;
 * they only perform concurrent-map and immutable-ranking lookups.
 */
final class PlaceholderEconomyCache implements PlaceholderBalanceView, Listener, AutoCloseable {
    static final long REFRESH_INTERVAL_TICKS = 20L * 20L;

    private final SodaEconomy plugin;
    private final EconomyTransactionApi economyApi;
    private final boolean bankingEnabled;
    private final ConcurrentHashMap<UUID, Long> walletBalances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> bankBalances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> walletLocalVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> bankLocalVersions = new ConcurrentHashMap<>();
    private final AtomicLong localRevision = new AtomicLong();
    private final Object snapshotMergeLock = new Object();
    private final AtomicReference<Map<UUID, Integer>> leaderboardPositions = new AtomicReference<>(Map.of());
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean leaderboardRebuildScheduled = new AtomicBoolean();
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();
    private final AtomicBoolean walletSnapshotAvailable = new AtomicBoolean();
    private final AtomicBoolean bankSnapshotAvailable = new AtomicBoolean();
    private volatile int refreshTaskId = -1;
    private volatile boolean closed;

    PlaceholderEconomyCache(SodaEconomy plugin, EconomyTransactionApi economyApi, boolean bankingEnabled) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.economyApi = Objects.requireNonNull(economyApi, "economyApi");
        this.bankingEnabled = bankingEnabled;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        refreshAsync();
        refreshTaskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::refreshAsync, REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS).getTaskId();
    }

    @Override
    public long walletBalanceMinor(UUID playerId) {
        if (playerId == null) return 0L;
        return walletBalances.getOrDefault(playerId, 0L);
    }

    @Override
    public long bankBalanceMinor(UUID playerId) {
        if (playerId == null || !bankingEnabled) return 0L;
        return bankBalances.getOrDefault(playerId, 0L);
    }

    @Override
    public int leaderboardPosition(UUID playerId) {
        if (playerId == null) return 0;
        return leaderboardPositions.get().getOrDefault(playerId, 0);
    }

    @Override
    public boolean walletSnapshotAvailable() {
        return walletSnapshotAvailable.get();
    }

    @Override
    public boolean bankSnapshotAvailable() {
        return !bankingEnabled || bankSnapshotAvailable.get();
    }

    @EventHandler
    public void onWalletTransaction(WalletTransactionEvent event) {
        if (closed) return;
        TransactionRecord record = event.getTransaction();
        synchronized (snapshotMergeLock) {
            long revision = localRevision.incrementAndGet();
            if (record.sourcePlayerId() != null && record.sourceBalanceAfterMinor() != null) {
                walletBalances.put(record.sourcePlayerId(), record.sourceBalanceAfterMinor());
                walletLocalVersions.put(record.sourcePlayerId(), revision);
            }
            if (record.targetPlayerId() != null && record.targetBalanceAfterMinor() != null) {
                walletBalances.put(record.targetPlayerId(), record.targetBalanceAfterMinor());
                walletLocalVersions.put(record.targetPlayerId(), revision);
            }
            updateBankFromWalletMovement(record, revision);
        }
        scheduleLeaderboardRebuild();
    }

    private void updateBankFromWalletMovement(TransactionRecord record, long revision) {
        if (!bankingEnabled || record.appliedAmountMinor() <= 0L) return;
        UUID playerId;
        long delta;
        if (record.type() == TransactionType.WALLET_TO_BANK) {
            playerId = record.sourcePlayerId();
            delta = record.appliedAmountMinor();
        } else if (record.type() == TransactionType.BANK_TO_WALLET) {
            playerId = record.targetPlayerId();
            delta = -record.appliedAmountMinor();
        } else {
            return;
        }
        if (playerId == null) return;
        bankLocalVersions.put(playerId, revision);
        // A bank journal delta has no bank-before value. Never invent a base balance before the
        // first authoritative snapshot has loaded; placeholders remain unavailable until that snapshot succeeds.
        bankBalances.computeIfPresent(playerId, (ignored, current) -> {
            try {
                return Math.max(0L, Math.addExact(current, delta));
            } catch (ArithmeticException overflow) {
                return current;
            }
        });
    }

    private void refreshAsync() {
        refreshNow();
    }

    CompletableFuture<Boolean> refreshNow() {
        if (closed || !refreshInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false);
        }
        long revisionAtStart = localRevision.get();

        CompletableFuture<Boolean> walletRefresh = safeSnapshotRead(economyApi::getStoredBalancesMinorUnits)
                .handle((snapshot, failure) -> {
                    if (failure != null || snapshot == null) return false;
                    synchronized (snapshotMergeLock) {
                        mergeSnapshot(walletBalances, walletLocalVersions, snapshot, revisionAtStart);
                    }
                    walletSnapshotAvailable.set(true);
                    rebuildLeaderboard();
                    return true;
                });

        CompletableFuture<Boolean> bankRefresh = bankingEnabled
                ? safeSnapshotRead(economyApi::getStoredBankBalancesMinorUnits).handle((snapshot, failure) -> {
                    if (failure != null || snapshot == null) return false;
                    synchronized (snapshotMergeLock) {
                        mergeSnapshot(bankBalances, bankLocalVersions, snapshot, revisionAtStart);
                    }
                    bankSnapshotAvailable.set(true);
                    return true;
                })
                : CompletableFuture.completedFuture(true);

        return walletRefresh.thenCombine(bankRefresh, (walletSucceeded, bankSucceeded) ->
                        walletSucceeded && bankSucceeded)
                .whenComplete((allSucceeded, ignored) -> {
                    refreshInFlight.set(false);
                    if (closed) return;
                    if (!Boolean.TRUE.equals(allSucceeded)) {
                        if (refreshFailureLogged.compareAndSet(false, true)) {
                            plugin.getLogger().warning("[PlaceholderAPI] Could not refresh all economy presentation data; "
                                    + "the last successful snapshot for each data source remains active.");
                        }
                        return;
                    }
                    refreshFailureLogged.set(false);
                });
    }

    private static CompletableFuture<Map<UUID, Long>> safeSnapshotRead(
            Supplier<CompletableFuture<Map<UUID, Long>>> read) {
        try {
            CompletableFuture<Map<UUID, Long>> future = read.get();
            return future == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("Economy snapshot read returned no future"))
                    : future;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void scheduleLeaderboardRebuild() {
        if (closed || !leaderboardRebuildScheduled.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            leaderboardRebuildScheduled.set(false);
            if (!closed) rebuildLeaderboard();
        }, 20L);
    }

    private void rebuildLeaderboard() {
        leaderboardPositions.set(LeaderboardRanking.positions(Map.copyOf(walletBalances)));
    }

    private static void mergeSnapshot(ConcurrentHashMap<UUID, Long> destination,
                                      ConcurrentHashMap<UUID, Long> localVersions,
                                      Map<UUID, Long> source, long revisionAtStart) {
        Map<UUID, Long> safe = source == null ? Map.of() : new HashMap<>(source);
        destination.keySet().removeIf(key -> !safe.containsKey(key)
                && localVersions.getOrDefault(key, 0L) <= revisionAtStart);
        safe.forEach((key, value) -> {
            if (localVersions.getOrDefault(key, 0L) <= revisionAtStart) {
                destination.put(key, value);
            }
        });
        localVersions.entrySet().removeIf(entry -> entry.getValue() <= revisionAtStart);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        int taskId = refreshTaskId;
        refreshTaskId = -1;
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        walletBalances.clear();
        bankBalances.clear();
        walletLocalVersions.clear();
        bankLocalVersions.clear();
        leaderboardPositions.set(Map.of());
        walletSnapshotAvailable.set(false);
        bankSnapshotAvailable.set(false);
    }
}
