package de.sodaeconomy.identity;

import java.util.UUID;

interface PlayerPlatformBridge {
    PlayerType playerType(UUID playerId);
    String name();
    boolean available();
}
