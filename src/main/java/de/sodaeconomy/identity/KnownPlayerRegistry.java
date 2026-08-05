package de.sodaeconomy.identity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Thread-safe in-memory index of persisted player identities. */
public final class KnownPlayerRegistry {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<UUID, PlayerIdentity> identities = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> names = new HashMap<>();

    public void replaceAll(Map<UUID, PlayerIdentity> values) {
        lock.writeLock().lock();
        try {
            identities.clear();
            names.clear();
            values.values().forEach(this::putLocked);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns the effective stored value after merging an update with a potentially newer record. */
    public PlayerIdentity upsert(PlayerIdentity candidate) {
        lock.writeLock().lock();
        try {
            PlayerIdentity existing = identities.get(candidate.playerId());
            PlayerIdentity effective = PlayerIdentity.merge(existing, candidate);
            if (existing != null && !existing.normalizedName().equals(effective.normalizedName())) {
                removeNameLocked(existing.normalizedName(), existing.playerId());
            }
            putLocked(effective);
            return effective;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<PlayerIdentity> find(UUID playerId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(identities.get(playerId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns a result only when the normalized name maps unambiguously to one UUID. */
    public Optional<PlayerIdentity> findUnique(String name) {
        String normalized;
        try {
            normalized = PlayerIdentity.normalizeName(name);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            Set<UUID> matches = names.get(normalized);
            if (matches == null || matches.size() != 1) {
                return Optional.empty();
            }
            return Optional.ofNullable(identities.get(matches.iterator().next()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<UUID, PlayerIdentity> findAll(Collection<UUID> playerIds) {
        lock.readLock().lock();
        try {
            Map<UUID, PlayerIdentity> result = new HashMap<>();
            for (UUID playerId : playerIds) {
                PlayerIdentity identity = identities.get(playerId);
                if (identity != null) result.put(playerId, identity);
            }
            return Map.copyOf(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<String> suggestNames(String prefix, int limit) {
        String normalizedPrefix;
        try {
            normalizedPrefix = prefix == null || prefix.isBlank() ? "" : PlayerIdentity.normalizeName(prefix);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<String> result = new ArrayList<>();
            identities.values().stream()
                    .filter(identity -> identity.normalizedName().startsWith(normalizedPrefix))
                    .sorted(Comparator.comparing(PlayerIdentity::normalizedName))
                    .map(PlayerIdentity::lastKnownName)
                    .distinct()
                    .limit(Math.max(0, limit))
                    .forEach(result::add);
            return List.copyOf(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<UUID, PlayerIdentity> snapshot() {
        lock.readLock().lock();
        try {
            return Map.copyOf(identities);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void putLocked(PlayerIdentity identity) {
        identities.put(identity.playerId(), identity);
        names.computeIfAbsent(identity.normalizedName(), ignored -> new LinkedHashSet<>())
                .add(identity.playerId());
    }

    private void removeNameLocked(String normalizedName, UUID playerId) {
        LinkedHashSet<UUID> matches = names.get(normalizedName);
        if (matches == null) return;
        matches.remove(playerId);
        if (matches.isEmpty()) names.remove(normalizedName);
    }


}
