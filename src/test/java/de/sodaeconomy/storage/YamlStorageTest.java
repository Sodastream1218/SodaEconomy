package de.sodaeconomy.storage;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlStorageTest extends MockBukkitTestBase {

    @Test
    void persistsCanonicalMinorUnitsAboveDoublePrecisionAcrossReinitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        long walletMinor = 9_007_199_254_740_995L;
        long bankMinor = 9_007_199_254_740_997L;
        YamlStorage storage = new YamlStorage();
        YamlStorage reloadedStorage = null;

        try {
            storage.init(plugin);
            storage.replaceMinorUnitSnapshot(Map.of(playerId, walletMinor), Map.of(playerId, bankMinor), java.util.List.of());
            storage.close();

            reloadedStorage = new YamlStorage();
            reloadedStorage.init(plugin);

            // Regression guard: this fails if canonical long values are widened through double before YAML writes.
            assertEquals(walletMinor, reloadedStorage.getAllBalanceMinorUnits().get(playerId));
            assertEquals(bankMinor, reloadedStorage.getAllBankBalanceMinorUnits().get(playerId));
        } finally {
            storage.close();
            if (reloadedStorage != null) reloadedStorage.close();
        }
    }

    @Test
    void persistsSetBalanceMinorUnitsAboveDoublePrecisionAcrossReinitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        double amount = 90_071_992_547_409.95D;
        long expectedMinor = de.sodaeconomy.Money.toMinorUnits(amount);
        YamlStorage storage = new YamlStorage();
        YamlStorage reloadedStorage = null;

        try {
            storage.init(plugin);
            storage.setBalance(playerId, amount);
            storage.close();

            reloadedStorage = new YamlStorage();
            reloadedStorage.init(plugin);

            // Regression guard: putAndPersist must take long, not double, or this exact value is rounded.
            assertEquals(expectedMinor, reloadedStorage.getAllBalanceMinorUnits().get(playerId));
        } finally {
            storage.close();
            if (reloadedStorage != null) reloadedStorage.close();
        }
    }

    @Test
    void persistsMainAndBankBalancesAcrossReinitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        YamlStorage storage = new YamlStorage();
        YamlStorage reloadedStorage = null;

        try {
            storage.init(plugin);

            assertNull(storage.getBalance(playerId));
            assertEquals(100D, storage.getOrCreateBalance(playerId, 100D));
            storage.setBankBalance(playerId, 20D);
            assertTrue(storage.transferMainAndBank(playerId, true, 10D));
            storage.close();

            reloadedStorage = new YamlStorage();
            reloadedStorage.init(plugin);

            assertEquals(90D, reloadedStorage.getBalance(playerId));
            assertEquals(30D, reloadedStorage.getBankBalance(playerId));
        } finally {
            storage.close();
            if (reloadedStorage != null) reloadedStorage.close();
        }
    }
    @Test
    void persistsPlayerIdentityAcrossReinitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity identity = PlayerIdentity.of(playerId, ".BedrockUser", Instant.ofEpochMilli(1234),
                PlayerType.BEDROCK);
        YamlStorage storage = new YamlStorage();
        YamlStorage reloaded = null;

        try {
            storage.init(plugin);
            storage.upsertPlayerIdentity(identity);
            storage.close();

            reloaded = new YamlStorage();
            reloaded.init(plugin);
            assertEquals(identity, reloaded.findPlayerIdentity(playerId).orElseThrow());
        } finally {
            storage.close();
            if (reloaded != null) reloaded.close();
        }
    }

}
