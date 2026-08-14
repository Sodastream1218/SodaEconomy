package de.sodaeconomy.storage;

import de.sodaeconomy.Money;
import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.transaction.TransactionRecord;
import org.bukkit.plugin.java.JavaPlugin;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Common persistence contract for all SodaEconomy storage backends. It supports wallet and bank
 * accounts. Production implementations are {@link YamlStorage}, {@link SQLiteStorage}, and
 * {@link MySQLStorage}.
 */
public interface Storage {

    /**
     * Initializes the storage backend, including its file or database connection.
     *
     * @param plugin owning plugin instance
     * @throws Exception when initialization fails
     */
    void init(JavaPlugin plugin) throws Exception;

    // Wallet account

    /**
     * Gets a player's wallet balance.
     *
     * @param uuid player identifier
     * @return current balance, or {@code null} when no account exists
     * @throws Exception when reading fails
     */
    Double getBalance(UUID uuid) throws Exception;

    /**
     * Exact single-wallet lookup for internal services that must not round-trip canonical minor
     * units through Vault's or the legacy API's {@code double} representation.
     */
    default Long getBalanceMinorUnits(UUID uuid) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.getBalanceMinorUnits", Storage.class);
        Double balance = getBalance(uuid);
        return balance == null ? null : Money.toMinorUnits(balance);
    }

    /**
     * Returns the existing balance or creates the account exactly once.
     *
     * This is a legacy persistence operation. Use the central wallet transaction service so the
     * initial balance is journaled.
     */
    double getOrCreateBalance(UUID uuid, double initialBalance) throws Exception;

    /**
     * Low-level legacy persistence operation. Use the central wallet transaction service so
     * balance changes and journal records commit together.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    void setBalance(UUID uuid, double amount) throws Exception;

    /**
     * Loads every wallet balance.
     *
     * @return player-to-balance map
     * @throws Exception when reading fails
     */
    Map<UUID, Double> getAllBalances() throws Exception;

    /**
     * Persists wallet balances in the backend.
     *
     * @param balances player-to-balance map
     * @throws Exception when persistence fails
     */
    void saveAll(Map<UUID, Double> balances) throws Exception;

    /**
     * Exact wallet balances for internal storage migration. Implementations with canonical
     * minor-unit persistence override this method so storage switching never round-trips through
     * a {@code double}; the default keeps third-party legacy backends source-compatible.
     */
    default Map<UUID, Long> getAllBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.getAllBalanceMinorUnits", Storage.class);
        Map<UUID, Long> minorBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : getAllBalances().entrySet()) {
            minorBalances.put(entry.getKey(), Money.toMinorUnits(entry.getValue()));
        }
        return Map.copyOf(minorBalances);
    }


    // Player identity

    /** Loads every persisted player identity used for cross-server name resolution. */
    default Map<UUID, PlayerIdentity> getAllPlayerIdentities() throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.getAllPlayerIdentities", Storage.class);
        return Map.of();
    }

    /** Finds one persisted identity by UUID without creating a synthetic Bukkit account. */
    default Optional<PlayerIdentity> findPlayerIdentity(UUID playerId) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.findPlayerIdentity", Storage.class);
        return Optional.ofNullable(getAllPlayerIdentities().get(playerId));
    }

    /** Finds identities by normalized name. Multiple results are retained so collisions stay explicit. */
    default List<PlayerIdentity> findPlayerIdentitiesByNormalizedName(String normalizedName) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.findPlayerIdentitiesByNormalizedName", Storage.class);
        return getAllPlayerIdentities().values().stream()
                .filter(identity -> identity.normalizedName().equals(normalizedName))
                .toList();
    }

    /** Batch identity lookup used by leaderboards to avoid per-player database queries. */
    default Map<UUID, PlayerIdentity> findPlayerIdentities(Set<UUID> playerIds) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.findPlayerIdentities", Storage.class);
        Map<UUID, PlayerIdentity> identities = new LinkedHashMap<>();
        Map<UUID, PlayerIdentity> all = getAllPlayerIdentities();
        for (UUID playerId : playerIds) {
            PlayerIdentity identity = all.get(playerId);
            if (identity != null) identities.put(playerId, identity);
        }
        return Map.copyOf(identities);
    }

    /** Persists one trusted identity observation. External plugins must use PlayerIdentityApi read methods. */
    default void upsertPlayerIdentity(PlayerIdentity identity) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.upsertPlayerIdentity", Storage.class);
    }

    /** Replaces the identity registry during a verified storage migration. */
    default void replacePlayerIdentities(Map<UUID, PlayerIdentity> identities) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.replacePlayerIdentities", Storage.class);
        for (PlayerIdentity identity : identities.values()) upsertPlayerIdentity(identity);
    }

    // Bank account

    /**
     * Gets a player's bank balance.
     *
     * @param uuid player identifier
     * @return current bank balance, or {@code null} when no account exists
     * @throws Exception when reading fails
     */
    Double getBankBalance(UUID uuid) throws Exception;

    /**
     * Sets a player's bank balance through this low-level storage contract.
     *
     * @param uuid player identifier
     * @param amount balance to persist
     * @throws Exception when persistence fails
     */
    @Deprecated(since = "1.0", forRemoval = true)
    void setBankBalance(UUID uuid, double amount) throws Exception;

    /**
     * Loads every bank balance.
     *
     * @return player-to-bank-balance map
     * @throws Exception when reading fails
     */
    Map<UUID, Double> getAllBankBalances() throws Exception;

    /**
     * Persists bank balances in the backend.
     *
     * @param bankBalances player-to-bank-balance map
     * @throws Exception when persistence fails
     */
    void saveAllBank(Map<UUID, Double> bankBalances) throws Exception;

    /** Exact bank balances for internal storage migration; see {@link #getAllBalanceMinorUnits()}. */
    default Map<UUID, Long> getAllBankBalanceMinorUnits() throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.getAllBankBalanceMinorUnits", Storage.class);
        Map<UUID, Long> minorBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Double> entry : getAllBankBalances().entrySet()) {
            minorBalances.put(entry.getKey(), Money.toMinorUnits(entry.getValue()));
        }
        return Map.copyOf(minorBalances);
    }

    /**
     * Exact bank balance batch update used by interest processing. Production canonical backends
     * override this method; the default preserves legacy custom storage implementations.
     */
    default void saveAllBankMinorUnits(Map<UUID, Long> bankBalances) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.saveAllBankMinorUnits", Storage.class);
        Map<UUID, Double> legacyBankBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : bankBalances.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0L) {
                throw new IllegalArgumentException("Bank balances must contain non-negative minor-unit values");
            }
            legacyBankBalances.put(entry.getKey(), Money.fromMinorUnits(entry.getValue()));
        }
        saveAllBank(legacyBankBalances);
    }

    /**
     * Applies one exact bank-interest run. Local YAML and SQLite installations execute this
     * default implementation while holding the transaction service lock. MySQL overrides it to
     * coordinate the interval transactionally across every connected server instance.
     *
     * @param rate positive decimal interest rate, for example {@code 0.05} for five percent
     * @param maximumInterestMinor optional per-account cap in minor units, or zero for no cap
     * @param minimumInterval minimum time between coordinated runs; ignored by single-server stores
     * @param runAt timestamp used for distributed run coordination
     * @return whether this invocation executed and how many accounts changed
     * @throws Exception when reading or atomically persisting the bank balances fails
     */
    default BankInterestResult applyBankInterest(BigDecimal rate, long maximumInterestMinor,
                                                  Duration minimumInterval, Instant runAt) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.applyBankInterest", Storage.class);
        if (rate == null || rate.signum() <= 0 || maximumInterestMinor < 0L
                || minimumInterval == null || minimumInterval.isNegative() || runAt == null) {
            throw new IllegalArgumentException("The bank-interest parameters must be valid");
        }

        Map<UUID, Long> updatedBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : getAllBankBalanceMinorUnits().entrySet()) {
            UUID playerId = entry.getKey();
            Long balanceValue = entry.getValue();
            if (playerId == null || balanceValue == null || balanceValue < 0L) {
                throw new IllegalStateException("The storage returned an invalid bank balance");
            }
            long balanceMinor = balanceValue;
            if (balanceMinor == 0L) {
                continue;
            }

            try {
                long interestMinor = Money.toMinorUnits(Money.toBigDecimal(balanceMinor).multiply(rate));
                if (maximumInterestMinor > 0L) {
                    interestMinor = Math.min(interestMinor, maximumInterestMinor);
                }
                if (interestMinor <= 0L) {
                    continue;
                }
                updatedBalances.put(playerId, Math.addExact(balanceMinor, interestMinor));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                // One invalid legacy account must not prevent valid accounts from receiving interest.
            }
        }
        if (!updatedBalances.isEmpty()) {
            saveAllBankMinorUnits(Map.copyOf(updatedBalances));
        }
        return BankInterestResult.executed(updatedBalances.size());
    }

    /**
     * Atomically replaces the complete migration dataset. Production implementations override
     * this method so main and bank balances are committed together; the default keeps third-party
     * implementations source-compatible.
     */
    default void replaceSnapshot(StorageSnapshot snapshot) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.replaceSnapshot", Storage.class);
        if (!snapshot.walletTransactions().isEmpty()) {
            throw new UnsupportedOperationException("This storage does not support wallet transaction migration");
        }
        saveAll(snapshot.balances());
        saveAllBank(snapshot.bankBalances());
    }

    /**
     * Atomically imports the exact storage-migration snapshot. Production backends override this
     * method to keep their canonical minor units intact. The fallback remains for legacy custom
     * implementations and is intentionally documented as a compatibility path only.
     */
    default void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                          List<TransactionRecord> walletTransactions) throws Exception {
        replaceMinorUnitSnapshot(balances, bankBalances, walletTransactions, getAllPlayerIdentities());
    }

    /** Exact migration import including the cross-server player identity registry. */
    default void replaceMinorUnitSnapshot(Map<UUID, Long> balances, Map<UUID, Long> bankBalances,
                                          List<TransactionRecord> walletTransactions,
                                          Map<UUID, PlayerIdentity> playerIdentities) throws Exception {
        StorageAccessGuard.requireInternalCaller("Storage.replaceMinorUnitSnapshot", Storage.class);
        Map<UUID, Double> legacyBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            legacyBalances.put(entry.getKey(), Money.fromMinorUnits(entry.getValue()));
        }
        Map<UUID, Double> legacyBankBalances = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : bankBalances.entrySet()) {
            legacyBankBalances.put(entry.getKey(), Money.fromMinorUnits(entry.getValue()));
        }
        replaceSnapshot(new StorageSnapshot(legacyBalances, legacyBankBalances, walletTransactions));
        replacePlayerIdentities(playerIdentities);
    }

    /**
     * Low-level legacy persistence operation. Use the central wallet transaction service so
     * transfers and journal records commit together.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    boolean transferMain(UUID source, UUID target, double amount) throws Exception;

    /**
     * Low-level legacy persistence operation. Use the central wallet transaction service so
     * wallet-bank transfers and journal records commit together.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    boolean transferMainAndBank(UUID uuid, boolean mainToBank, double amount) throws Exception;


    // Lifecycle

    /** Closes the storage backend and releases file or database resources. */
    void close();

    /**
     * Returns whether storage debug logging is enabled.
     *
     * @return {@code true} when debug logging is enabled
     */
    boolean isDebug();
}
