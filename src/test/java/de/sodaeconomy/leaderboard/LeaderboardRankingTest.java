package de.sodaeconomy.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaderboardRankingTest {

    @Test
    void ranksByExactBalanceDescendingWithDeterministicUuidTieBreak() {
        UUID firstUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID richest = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Map<UUID, Long> balances = new LinkedHashMap<>();
        balances.put(secondUuid, 10_000L);
        balances.put(richest, 20_000L);
        balances.put(firstUuid, 10_000L);

        assertEquals(richest, LeaderboardRanking.sortedEntries(balances).get(0).getKey());
        assertEquals(firstUuid, LeaderboardRanking.sortedEntries(balances).get(1).getKey());
        assertEquals(secondUuid, LeaderboardRanking.sortedEntries(balances).get(2).getKey());

        Map<UUID, Integer> positions = LeaderboardRanking.positions(balances);
        assertEquals(1, positions.get(richest));
        assertEquals(2, positions.get(firstUuid));
        assertEquals(3, positions.get(secondUuid));
    }
}
