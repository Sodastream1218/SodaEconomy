package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.BankManager;
import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankCommandTest extends MockBukkitTestBase {
    private final List<TransactionService> transactionServices = new ArrayList<>();

    @AfterEach
    void closeTransactionServices() {
        transactionServices.forEach(TransactionService::close);
        transactionServices.clear();
    }

    @Test
    void rejectsNonFiniteDepositInputWithAUserFacingMessage() {
        InMemoryStorage storage = new InMemoryStorage();
        EconomyManager economy = createEconomy(storage);
        BankManager bank = new BankManager(storage, economy, plugin, false);
        BankCommand command = new BankCommand(bank, economy);
        PlayerMock player = server.addPlayer("Alice");
        economy.setBalance(player.getUniqueId(), 100D);

        assertTrue(command.onCommand(player, null, "bank", new String[]{"deposit", "Infinity"}));

        assertEquals(100D, economy.getBalance(player.getUniqueId()));
        assertEquals(0D, bank.getBankBalance(player.getUniqueId()));
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("invalid-amount"));
    }

    @Test
    void depositsMoneyForAPlayerAndReportsTheCompletedTransaction() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        EconomyManager economy = createEconomy(storage);
        BankManager bank = new BankManager(storage, economy, plugin, false);
        BankCommand command = new BankCommand(bank, economy);
        PlayerMock player = server.addPlayer("Alice");
        economy.setBalance(player.getUniqueId(), 100D);

        assertTrue(command.onCommand(player, null, "bank", new String[]{"deposit", "25"}));
        awaitAsyncCommandResult();

        assertEquals(75D, economy.getBalance(player.getUniqueId()));
        assertEquals(25D, bank.getBankBalance(player.getUniqueId()));
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "bank-deposit-success", "amount", economy.formatCurrency(25D)));
    }

    @Test
    void reportsFailedBankAdministrativePersistenceInsteadOfClaimingSuccess() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        EconomyManager economy = createEconomy(storage);
        BankManager bank = new BankManager(storage, economy, plugin, false) {
            @Override
            public CompletableFuture<Boolean> tryAdminSetBankBalanceAsynchronously(java.util.UUID playerId,
                                                                                     double amount) {
                return CompletableFuture.completedFuture(false);
            }
        };
        BankCommand command = new BankCommand(bank, economy);
        PlayerMock administrator = server.addPlayer("Administrator");
        administrator.addAttachment(plugin, "sodaeconomy.bank.admin", true);
        server.addPlayer("Target");

        assertTrue(command.onCommand(administrator, null, "bank", new String[]{"setbalance", "Target", "25"}));
        awaitAsyncCommandResult();

        administrator.assertSaid(plugin.getLanguageManager().getFormattedMessage("transaction-failed"));
    }

    private EconomyManager createEconomy(InMemoryStorage storage) {
        EconomyManager economy = new EconomyManager(storage, false, plugin);
        transactionServices.add(economy.getTransactionService());
        return economy;
    }
}
