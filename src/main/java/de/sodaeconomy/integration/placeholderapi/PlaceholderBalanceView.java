package de.sodaeconomy.integration.placeholderapi;

import java.util.UUID;

/** Cheap, thread-safe presentation snapshot consumed by PlaceholderAPI callbacks. */
interface PlaceholderBalanceView {
    long walletBalanceMinor(UUID playerId);
    long bankBalanceMinor(UUID playerId);
    int leaderboardPosition(UUID playerId);

    /** Whether at least one authoritative wallet snapshot has loaded successfully. */
    default boolean walletSnapshotAvailable() {
        return true;
    }

    /** Whether at least one authoritative bank snapshot has loaded successfully. */
    default boolean bankSnapshotAvailable() {
        return true;
    }
}
