package de.sodaeconomy.transaction;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable transaction actor. Player identity is represented by a UUID; the display name is
 * optional metadata and must never be used as an account identifier.
 */
public record TransactionOrigin(TransactionOriginType type, UUID playerId, String identifier) {
    private static final int MAX_IDENTIFIER_LENGTH = 128;

    public TransactionOrigin {
        type = Objects.requireNonNull(type, "type");
        identifier = requireIdentifier(identifier);
        if (type == TransactionOriginType.PLAYER && playerId == null) {
            throw new IllegalArgumentException("A player origin requires a player ID");
        }
        if (type != TransactionOriginType.PLAYER && playerId != null) {
            throw new IllegalArgumentException("Only player origins may have a player ID");
        }
    }

    public static TransactionOrigin player(UUID playerId, String playerName) {
        return new TransactionOrigin(TransactionOriginType.PLAYER, Objects.requireNonNull(playerId, "playerId"), playerName);
    }

    public static TransactionOrigin console() {
        return new TransactionOrigin(TransactionOriginType.CONSOLE, null, "CONSOLE");
    }

    public static TransactionOrigin api(String pluginName) {
        return new TransactionOrigin(TransactionOriginType.API, null, pluginName);
    }

    public static TransactionOrigin system(String subsystem) {
        return new TransactionOrigin(TransactionOriginType.SYSTEM, null, subsystem);
    }

    private static String requireIdentifier(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The origin identifier must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("The origin identifier is too long");
        }
        return normalized;
    }
}
