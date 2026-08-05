package de.sodaeconomy.identity;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted identity for a player account. */
public record PlayerIdentity(UUID playerId, String lastKnownName, String normalizedName,
                             Instant lastLoginAt, PlayerType playerType) {
    public static final int MAX_NAME_LENGTH = 128;

    public PlayerIdentity {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lastKnownName, "lastKnownName");
        Objects.requireNonNull(normalizedName, "normalizedName");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        Objects.requireNonNull(playerType, "playerType");
        if (lastKnownName.isBlank() || lastKnownName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The player name must contain between 1 and "
                    + MAX_NAME_LENGTH + " characters");
        }
        String canonical = normalizeName(lastKnownName);
        if (!canonical.equals(normalizedName)) {
            throw new IllegalArgumentException("The normalized player name does not match the player name");
        }
    }

    public static PlayerIdentity of(UUID playerId, String lastKnownName, Instant lastLoginAt, PlayerType type) {
        return new PlayerIdentity(playerId, lastKnownName, normalizeName(lastKnownName), lastLoginAt, type);
    }

    public static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("The player name is invalid");
        }
        return normalized;
    }
    /**
     * Merges a trusted observation without allowing an instance that lacks platform information to
     * downgrade a previously confirmed Bedrock identity. Name and login time are last-write-wins.
     */
    public static PlayerIdentity merge(PlayerIdentity existing, PlayerIdentity candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (existing == null) return candidate;
        if (!existing.playerId().equals(candidate.playerId())) {
            throw new IllegalArgumentException("Player identities with different UUIDs cannot be merged");
        }
        boolean candidateIsNewer = !candidate.lastLoginAt().isBefore(existing.lastLoginAt());
        String name = candidateIsNewer ? candidate.lastKnownName() : existing.lastKnownName();
        Instant login = candidateIsNewer ? candidate.lastLoginAt() : existing.lastLoginAt();
        PlayerType type = mergeType(existing.playerType(), candidate.playerType());
        return PlayerIdentity.of(existing.playerId(), name, login, type);
    }

    private static PlayerType mergeType(PlayerType existing, PlayerType candidate) {
        if (existing == PlayerType.BEDROCK || candidate == PlayerType.BEDROCK) return PlayerType.BEDROCK;
        if (candidate != PlayerType.UNKNOWN) return candidate;
        return existing;
    }

}
