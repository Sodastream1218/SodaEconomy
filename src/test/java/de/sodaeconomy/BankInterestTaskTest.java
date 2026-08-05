package de.sodaeconomy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.sodaeconomy.support.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankInterestTaskTest {
    private BankManager bank;
    private EconomyManager economy;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        SodaEconomy plugin = MockBukkit.load(SodaEconomy.class);
        InMemoryStorage storage = new InMemoryStorage();
        economy = new EconomyManager(storage, false, plugin);
        bank = new BankManager(storage, economy, plugin, false);
    }

    @AfterEach
    void tearDown() {
        if (economy != null) economy.getTransactionService().close();
        MockBukkit.unmock();
    }

    @Test
    void delegatesInterestProcessingToTheBankManager() {
        UUID playerId = UUID.randomUUID();
        bank.setBankBalance(playerId, 100D);

        new BankInterestTask(bank, 0.10D, 0D).run();

        assertEquals(110D, bank.getBankBalance(playerId));
    }
}
