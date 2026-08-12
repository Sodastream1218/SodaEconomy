package de.sodaeconomy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.transaction.TransactionOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Test
    void asynchronousBankFacadeMethodsUseTheSameTransactionBoundaries() throws Exception {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 200D);

        assertTrue(bank.depositTransactionAsynchronously(playerId, 50D,
                TransactionOrigin.system("BankManagerTest")).get(5, TimeUnit.SECONDS).isSuccessful());
        assertEquals(150D, economy.getBalance(playerId));
        assertEquals(50D, bank.getBankBalanceAsynchronously(playerId).get(5, TimeUnit.SECONDS));

        assertTrue(bank.withdrawTransactionAsynchronously(playerId, 25D,
                TransactionOrigin.system("BankManagerTest")).get(5, TimeUnit.SECONDS).isSuccessful());
        assertEquals(175D, economy.getBalance(playerId));
        assertEquals(25D, bank.getBankBalance(playerId));

        assertTrue(bank.tryAdminSetBankBalanceAsynchronously(playerId, 75D).get(5, TimeUnit.SECONDS));
        assertEquals(75D, bank.getBankBalance(playerId));

        Map<UUID, Double> balances = bank.getAllBankBalances();
        assertEquals(75D, balances.get(playerId));
    }

    @Test
    void administrativeCompatibilitySetterAndIntervalInterestDelegateToTheService() {
        UUID playerId = UUID.randomUUID();

        bank.adminSetBankBalance(playerId, 100D);

        assertEquals(100D, bank.getBankBalance(playerId));
        assertEquals(1, bank.applyInterest(0.10D, 50D, Duration.ZERO));
        assertEquals(110D, bank.getBankBalance(playerId));
    }
}
