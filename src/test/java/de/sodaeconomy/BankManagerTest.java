package de.sodaeconomy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.sodaeconomy.support.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankManagerTest {
    private EconomyManager economy;
    private BankManager bank;

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
    void depositsAtomicallyBetweenMainAndBankAccounts() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 100D);

        assertTrue(bank.deposit(playerId, 40D));

        assertEquals(60D, economy.getBalance(playerId));
        assertEquals(40D, bank.getBankBalance(playerId));
    }

    @Test
    void rejectsWithdrawalThatWouldExceedMainAccountMaximum() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, economy.getMaxBalance() - 5D);
        bank.setBankBalance(playerId, 20D);

        assertFalse(bank.withdraw(playerId, 10D));

        assertEquals(economy.getMaxBalance() - 5D, economy.getBalance(playerId));
        assertEquals(20D, bank.getBankBalance(playerId));
    }

    @Test
    void appliesCappedInterestOnlyToPositiveBankBalances() {
        UUID eligible = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        bank.setBankBalance(eligible, 100D);
        bank.setBankBalance(empty, 0D);

        assertEquals(1, bank.applyInterest(0.50D, 20D));

        assertEquals(120D, bank.getBankBalance(eligible));
        assertEquals(0D, bank.getBankBalance(empty));
    }

    @Test
    void exposesRejectedBankOnlyAdministrativeUpdatesToTheCaller() {
        UUID playerId = UUID.randomUUID();

        assertFalse(bank.tryAdminSetBankBalance(playerId, Double.NaN));
        assertEquals(0D, bank.getBankBalance(playerId));
    }
}
