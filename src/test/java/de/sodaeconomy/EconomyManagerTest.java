package de.sodaeconomy;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyManagerTest {
    private SodaEconomy plugin;
    private InMemoryStorage storage;
    private EconomyManager economy;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        plugin = MockBukkit.load(SodaEconomy.class);
        storage = new InMemoryStorage();
        economy = new EconomyManager(storage, false, plugin);
    }

    @AfterEach
    void tearDown() {
        if (economy != null) economy.getTransactionService().close();
        MockBukkit.unmock();
    }

    @Test
    void createsNewAccountsWithConfiguredStartingBalance() {
        UUID playerId = UUID.randomUUID();

        assertEquals(economy.getStartingBalance(), economy.getBalance(playerId));
        assertEquals(economy.getStartingBalance(), economy.getBalance(playerId));
        assertEquals(economy.getStartingBalance(), storage.getBalance(playerId));
    }

    @Test
    void capsBalanceAtConfiguredMaximum() {
        UUID playerId = UUID.randomUUID();

        economy.setBalance(playerId, economy.getMaxBalance() + 1D);

        assertEquals(economy.getMaxBalance(), economy.getBalance(playerId));
    }

    @Test
    void rejectsTransfersThatWouldExceedTheTargetMaximumWithoutMutatingEitherAccount() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        economy.setBalance(source, 100D);
        economy.setBalance(target, economy.getMaxBalance() - 10D);

        assertFalse(economy.transfer(source, target, 20D));

        assertEquals(100D, economy.getBalance(source));
        assertEquals(economy.getMaxBalance() - 10D, economy.getBalance(target));
    }

    @Test
    void transfersTheFullAmountWhenBothAccountsAreEligible() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        economy.setBalance(source, 100D);
        economy.setBalance(target, 50D);

        assertTrue(economy.transfer(source, target, 25D));

        assertEquals(75D, economy.getBalance(source));
        assertEquals(75D, economy.getBalance(target));
    }

    @Test
    void ignoresInvalidBalanceMutations() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 50D);

        economy.setBalance(playerId, Double.NaN);
        economy.addBalance(playerId, Double.POSITIVE_INFINITY);

        assertEquals(50D, economy.getBalance(playerId));
    }

    @Test
    void addsPositiveBalancesThroughTheCentralWalletLedger() throws Exception {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 50D);

        economy.addBalance(playerId, 12.50D);

        assertEquals(62.50D, economy.getBalance(playerId));
        assertTrue(storage.getAllWalletTransactions().stream()
                .anyMatch(record -> record.type() == TransactionType.API_DEPOSIT
                        && playerId.equals(record.targetPlayerId())
                        && record.appliedAmountMinor() == 1_250L));
    }

    @Test
    void removesOnlyAnAvailablePositiveAmount() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 50D);

        assertTrue(economy.removeBalance(playerId, 20D));
        assertEquals(30D, economy.getBalance(playerId));
        assertFalse(economy.removeBalance(playerId, 31D));
        assertFalse(economy.removeBalance(playerId, Double.NaN));

        assertEquals(30D, economy.getBalance(playerId));
    }

    @Test
    void rejectsTransfersWhenTheSourceDoesNotHaveEnoughMoney() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        economy.setBalance(source, 10D);
        economy.setBalance(target, 20D);

        assertFalse(economy.transfer(source, target, 11D));

        assertEquals(10D, economy.getBalance(source));
        assertEquals(20D, economy.getBalance(target));
    }

    @Test
    void resetsBalancesAndFormatsValidAndInvalidAmounts() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 20D);

        economy.resetBalance(playerId);

        assertEquals(economy.getStartingBalance(), economy.getBalance(playerId));
        assertEquals("$12.35", economy.formatCurrency(12.345D));
        assertEquals("$0.00", economy.formatCurrency(Double.NaN));
    }

    @Test
    void movesFundsBetweenWalletAndBankThroughTheTransactionService() {
        UUID playerId = UUID.randomUUID();
        economy.setBalance(playerId, 100D);

        assertTrue(economy.transferMainAndBank(playerId, true, 25D));
        assertTrue(economy.transferMainAndBank(playerId, false, 10D));

        assertEquals(85D, economy.getBalance(playerId));
        assertEquals(15D, storage.getBankBalance(playerId));
        assertTrue(storage.getAllWalletTransactions().stream()
                .anyMatch(record -> record.type() == TransactionType.WALLET_TO_BANK));
        assertTrue(storage.getAllWalletTransactions().stream()
                .anyMatch(record -> record.type() == TransactionType.BANK_TO_WALLET));
    }
}
