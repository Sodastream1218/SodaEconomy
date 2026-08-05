package de.sodaeconomy.identity;

import java.util.UUID;

final class NoopPlayerPlatformBridge implements PlayerPlatformBridge {
    @Override public PlayerType playerType(UUID playerId) { return PlayerType.JAVA; }
    @Override public String name() { return "Bukkit"; }
    @Override public boolean available() { return false; }
}
