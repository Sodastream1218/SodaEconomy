package de.sodaeconomy.integration.vault;

import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.Money;
import de.sodaeconomy.SodaEconomy;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerIdentityApi;
import de.sodaeconomy.language.LanguageManager;
import de.sodaeconomy.transaction.DurableOperation;
import de.sodaeconomy.transaction.TransactionFailureReason;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.WalletAccountLookup;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/** Vault 1.7.1 economy facade backed exclusively by SodaEconomy's durable transaction service. */
@SuppressWarnings("deprecation")
public final class VaultEconomyProvider implements Economy {
    private static final Map<String, String> DEPOSIT_METADATA = Map.of(
            "integration", "vault", "operation", "deposit", "vault_api", "1.7.1");
    private static final Map<String, String> WITHDRAW_METADATA = Map.of(
            "integration", "vault", "operation", "withdraw", "vault_api", "1.7.1");
    private static final long WARNING_RATE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final SodaEconomy plugin;
    private final VaultTransactionGateway gateway;
    private final PlayerIdentityApi identities;
    private final EconomyManager economy;
    private final LanguageManager language;
    private final VaultIntegrationSettings settings;
    private final Duration operationTimeout;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong lastSlowWarningNanos = new AtomicLong(Long.MIN_VALUE);

    VaultEconomyProvider(SodaEconomy plugin, VaultTransactionGateway gateway,
                                PlayerIdentityApi identities, EconomyManager economy,
                                LanguageManager language, VaultIntegrationSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.language = Objects.requireNonNull(language, "language");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.operationTimeout = Duration.ofMillis(settings.operationTimeoutMillis());
    }

    void deactivate() { active.set(false); }

    @Override public boolean isEnabled() { return active.get() && plugin.isEnabled(); }
    @Override public String getName() { return "SodaEconomy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return economy.formatCurrency(amount); }
    @Override public String currencyNamePlural() { return economy.getCurrencySymbol(); }
    @Override public String currencyNameSingular() { return economy.getCurrencySymbol(); }

    @Override public boolean hasAccount(String playerName) {
        return resolveKnownPlayerId(playerName).map(this::hasAccount).orElse(false);
    }
    private boolean hasAccount(UUID playerId) {
        WalletAccountLookup lookup = awaitRead(gateway.lookup(playerId), "hasAccount", null);
        return lookup != null && lookup.exists();
    }

    @Override public boolean hasAccount(OfflinePlayer player) {
        return player != null && hasAccount(player.getUniqueId());
    }
    @Override public boolean hasAccount(String playerName, String worldName) { return hasAccount(playerName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return hasAccount(player); }

    @Override public double getBalance(String playerName) {
        return resolveKnownPlayerId(playerName).map(this::getBalance).orElse(0D);
    }
    private double getBalance(UUID playerId) {
        WalletAccountLookup lookup = awaitRead(gateway.lookup(playerId), "getBalance", null);
        return lookup == null || !lookup.exists() ? 0D : Money.fromMinorUnits(lookup.balanceMinor());
    }

    @Override public double getBalance(OfflinePlayer player) {
        return player == null ? 0D : getBalance(player.getUniqueId());
    }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) {
        return resolveKnownPlayerId(playerName).map(playerId -> has(playerId, amount)).orElse(false);
    }
    private boolean has(UUID playerId, double amount) {
        Long requested = validatedNonNegativeMinor(amount);
        if (requested == null) return false;
        WalletAccountLookup lookup = awaitRead(gateway.lookup(playerId), "has", null);
        return lookup != null && lookup.exists() && lookup.balanceMinor() >= requested;
    }

    @Override public boolean has(OfflinePlayer player, double amount) {
        return player != null && has(player.getUniqueId(), amount);
    }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) {
        Optional<UUID> playerId = resolveKnownPlayerId(playerName);
        return playerId.map(value -> mutate(value, amount, false))
                .orElseGet(() -> failure(amount, 0D, "vault-player-not-known"));
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) return failure(amount, 0D, "vault-player-not-known");
        return mutate(player.getUniqueId(), amount, false);
    }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) {
        Optional<UUID> playerId = resolveKnownPlayerId(playerName);
        return playerId.map(value -> mutate(value, amount, true))
                .orElseGet(() -> failure(amount, 0D, "vault-player-not-known"));
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) return failure(amount, 0D, "vault-player-not-known");
        return mutate(player.getUniqueId(), amount, true);
    }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override public boolean createPlayerAccount(String playerName) {
        return resolveKnownPlayerId(playerName).map(this::createPlayerAccount).orElse(false);
    }
    private boolean createPlayerAccount(UUID playerId) {
        long started = System.nanoTime();
        DurableOperation<de.sodaeconomy.transaction.WalletAccountState> operation =
                gateway.createAccount(playerId, operationTimeout);
        try {
            de.sodaeconomy.transaction.WalletAccountState result = awaitMutation(operation, "createPlayerAccount");
            warnIfSlow("createPlayerAccount", started, false);
            return result != null && result.created();
        } catch (Exception failure) {
            logFailure("createPlayerAccount", failure);
            return false;
        }
    }

    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null && createPlayerAccount(player.getUniqueId());
    }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return createPlayerAccount(playerName); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return createPlayerAccount(player); }

    @Override public EconomyResponse createBank(String name, String player) { return notImplemented(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notImplemented(); }
    @Override public EconomyResponse deleteBank(String name) { return notImplemented(); }
    @Override public EconomyResponse bankBalance(String name) { return notImplemented(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return notImplemented(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return notImplemented(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return notImplemented(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return notImplemented(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notImplemented(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return notImplemented(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notImplemented(); }
    @Override public List<String> getBanks() { return List.of(); }

    private EconomyResponse mutate(UUID playerId, double amount, boolean deposit) {
        Long requestedMinor = validatedPositiveMinor(amount);
        if (requestedMinor == null) {
            return failure(amount, currentBalance(playerId), "vault-invalid-amount");
        }
        long started = System.nanoTime();
        DurableOperation<TransactionResult> operation = deposit
                ? gateway.deposit(playerId, Money.fromMinorUnits(requestedMinor), DEPOSIT_METADATA, operationTimeout)
                : gateway.withdraw(playerId, Money.fromMinorUnits(requestedMinor), WITHDRAW_METADATA, operationTimeout);
        try {
            TransactionResult result = awaitMutation(operation, deposit ? "deposit" : "withdraw");
            boolean exceededConfiguredTimeout = elapsedMillis(started) >= settings.operationTimeoutMillis();
            warnIfSlow(deposit ? "deposit" : "withdraw", started, exceededConfiguredTimeout);
            if (result == null) return failure(amount, currentBalance(playerId), "vault-storage-failure");
            if (!result.isSuccessful()) {
                return failure(0D, balanceFromResult(result, playerId), keyFor(result.failureReason()));
            }
            TransactionRecord record = result.transaction();
            double applied = Money.fromMinorUnits(record.appliedAmountMinor());
            double balance = balanceFromRecord(record, playerId);
            return new EconomyResponse(applied, balance, EconomyResponse.ResponseType.SUCCESS, null);
        } catch (SafeTimeoutException timeout) {
            warnTimeout(deposit ? "deposit" : "withdraw", timeout);
            return failure(0D, currentBalance(playerId), "vault-operation-timeout");
        } catch (Exception failure) {
            logFailure(deposit ? "deposit" : "withdraw", failure);
            return failure(0D, currentBalance(playerId), "vault-storage-failure");
        }
    }

    /**
     * A timeout can safely fail only while the operation is still queued. Once a storage mutation
     * has started, returning FAILURE would permit a later commit to look like a ghost transaction,
     * so the adapter waits for the definitive result and emits a slow-call warning instead.
     */
    private <T> T awaitMutation(DurableOperation<T> operation, String operationName) throws Exception {
        try {
            return operation.completion().get(settings.operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            if (operation.cancelBeforeStart()) throw new SafeTimeoutException(operationName, timeout);
            plugin.getLogger().warning("[Vault] " + operationName + " exceeded the configured timeout after durable "
                    + "execution had already started. Waiting for the definitive result to avoid an ambiguous transaction.");
            return operation.completion().get();
        } catch (ExecutionException exception) {
            throw unwrap(exception);
        } catch (CancellationException exception) {
            throw new SafeTimeoutException(operationName, exception);
        } catch (InterruptedException exception) {
            if (operation.cancelBeforeStart()) {
                Thread.currentThread().interrupt();
                throw new SafeTimeoutException(operationName, exception);
            }
            boolean interrupted = true;
            try {
                return operation.completion().join();
            } catch (java.util.concurrent.CompletionException completionFailure) {
                Throwable cause = completionFailure.getCause();
                if (cause instanceof Exception checked) throw checked;
                throw new IllegalStateException(cause);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private <T> T awaitRead(CompletableFuture<T> future, String operation, T fallback) {
        long started = System.nanoTime();
        try {
            T value = future.get(settings.operationTimeoutMillis(), TimeUnit.MILLISECONDS);
            warnIfSlow(operation, started, false);
            return value;
        } catch (Exception failure) {
            future.cancel(false);
            logFailure(operation, failure instanceof ExecutionException execution ? unwrap(execution) : failure);
            return fallback;
        }
    }

    private Optional<UUID> resolveKnownPlayerId(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        Optional<PlayerIdentity> identity = awaitRead(identities.resolve(playerName), "resolvePlayer", Optional.empty());
        return identity.map(PlayerIdentity::playerId);
    }

    private double currentBalance(UUID playerId) {
        WalletAccountLookup lookup = awaitRead(gateway.lookup(playerId), "readFailureBalance", null);
        return lookup == null || !lookup.exists() ? 0D : Money.fromMinorUnits(lookup.balanceMinor());
    }

    private static Long validatedPositiveMinor(double amount) {
        try {
            long minor = Money.toMinorUnits(amount);
            return amount > 0D && minor > 0L ? minor : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long validatedNonNegativeMinor(double amount) {
        try {
            long minor = Money.toMinorUnits(amount);
            return amount >= 0D && minor >= 0L ? minor : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private double balanceFromResult(TransactionResult result, UUID playerId) {
        return result.transaction() == null ? currentBalance(playerId) : balanceFromRecord(result.transaction(), playerId);
    }

    private static double balanceFromRecord(TransactionRecord record, UUID playerId) {
        if (playerId.equals(record.sourcePlayerId())) {
            Long value = record.sourceBalanceAfterMinor() != null ? record.sourceBalanceAfterMinor()
                    : record.sourceBalanceBeforeMinor();
            return value == null ? 0D : Money.fromMinorUnits(value);
        }
        Long value = record.targetBalanceAfterMinor() != null ? record.targetBalanceAfterMinor()
                : record.targetBalanceBeforeMinor();
        return value == null ? 0D : Money.fromMinorUnits(value);
    }

    private String keyFor(TransactionFailureReason reason) {
        return switch (reason == null ? TransactionFailureReason.STORAGE_FAILURE : reason) {
            case INVALID_AMOUNT, INVALID_REQUEST -> "vault-invalid-amount";
            case INSUFFICIENT_FUNDS -> "vault-insufficient-funds";
            case MAXIMUM_BALANCE_EXCEEDED -> "vault-maximum-balance";
            case ACCOUNT_NOT_FOUND -> "vault-player-not-known";
            case SERVICE_STOPPING -> "vault-service-stopping";
            default -> "vault-storage-failure";
        };
    }

    private EconomyResponse failure(double amount, double balance, String messageKey) {
        return new EconomyResponse(safeNonNegativeAmount(amount), safeNonNegativeAmount(balance),
                EconomyResponse.ResponseType.FAILURE, language.getFormattedMessage(messageKey));
    }

    private static double safeNonNegativeAmount(double value) {
        try {
            return value >= 0D ? Money.normalize(value) : 0D;
        } catch (IllegalArgumentException exception) {
            return 0D;
        }
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0D, 0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                language.getFormattedMessage("vault-bank-not-supported"));
    }

    private void warnIfSlow(String operation, long startedNanos, boolean exceededTimeout) {
        long elapsed = elapsedMillis(startedNanos);
        if (settings.warnAfterMillis() <= 0L || elapsed < settings.warnAfterMillis()) return;
        long now = System.nanoTime();
        long previous = lastSlowWarningNanos.get();
        if (now - previous < WARNING_RATE_LIMIT_NANOS || !lastSlowWarningNanos.compareAndSet(previous, now)) return;
        plugin.getLogger().warning("[Vault] Slow " + operation + " call completed in " + elapsed + " ms"
                + (exceededTimeout ? " after execution had already started; the definitive result was preserved." : "."));
    }

    private void warnTimeout(String operation, Exception failure) {
        plugin.getLogger().log(Level.WARNING, "[Vault] " + operation + " timed out before any wallet mutation started.",
                plugin.getConfigManager().isIntegrationsDebug() ? failure : null);
    }

    private void logFailure(String operation, Throwable failure) {
        if (plugin.getConfigManager().isIntegrationsDebug()) {
            plugin.getLogger().log(Level.WARNING, "[Vault] " + operation + " failed.", failure);
        } else {
            plugin.getLogger().warning("[Vault] " + operation + " failed: " + summarize(failure));
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static Exception unwrap(ExecutionException exception) {
        Throwable cause = exception.getCause();
        return cause instanceof Exception checked ? checked : new IllegalStateException(cause);
    }

    private static String summarize(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) current = current.getCause();
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank() ? (current == null ? "unknown failure" : current.getClass().getSimpleName()) : message;
    }

    private static final class SafeTimeoutException extends Exception {
        private static final long serialVersionUID = 1L;
        private SafeTimeoutException(String operation, Throwable cause) {
            super(operation + " timed out before execution", cause);
        }
    }
}
