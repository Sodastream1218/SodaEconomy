package de.sodaeconomy.leaderboard;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic ranking rules shared by /topbalance and presentation integrations. */
public final class LeaderboardRanking {
    private static final Comparator<Map.Entry<UUID, Long>> ENTRY_ORDER =
            Map.Entry.<UUID, Long>comparingByValue().reversed()
                    .thenComparing(entry -> entry.getKey().toString());

    private LeaderboardRanking() { }

    public static List<Map.Entry<UUID, Long>> sortedEntries(Map<UUID, Long> balances) {
        if (balances == null || balances.isEmpty()) return List.of();
        return balances.entrySet().stream().sorted(ENTRY_ORDER).toList();
    }

    /** Returns one-based positions for the complete supplied balance snapshot. */
    public static Map<UUID, Integer> positions(Map<UUID, Long> balances) {
        List<Map.Entry<UUID, Long>> sorted = sortedEntries(balances);
        if (sorted.isEmpty()) return Map.of();
        Map<UUID, Integer> positions = new LinkedHashMap<>(sorted.size());
        int position = 1;
        for (Map.Entry<UUID, Long> entry : sorted) {
            positions.put(entry.getKey(), position++);
        }
        return Map.copyOf(positions);
    }
}
