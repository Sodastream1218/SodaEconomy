package de.sodaeconomy.integration.placeholderapi;

import java.util.UUID;

/** Cheap, thread-safe presentation snapshot consumed by PlaceholderAPI callbacks. */
interface PlaceholderBalanceView {
    long walletBalanceMinor(UUID playerId);
    long bankBalanceMinor(UUID playerId);
    int leaderboardPosition(UUID playerId);
}
