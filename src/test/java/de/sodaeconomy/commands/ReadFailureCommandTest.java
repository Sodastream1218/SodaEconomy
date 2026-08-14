package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.BankManager;
import de.sodaeconomy.EconomyManager;
import de.sodaeconomy.support.InMemoryStorage;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFailureCommandTest extends MockBukkitTestBase {
    private InMemoryStorage storage;
    private TransactionService transactions;
    private EconomyManager economy;

    @BeforeEach
    void createFailingReadService() {
        storage = new InMemoryStorage();
        transactions = new TransactionService(plugin, storage, 1_000D, 0D);
        economy = new EconomyManager(storage, false, plugin, transactions, plugin.getPlayerIdentityApi());
        storage.setFailReads(true);
    }

    @AfterEach
    void closeFailingReadService() {
        if (transactions != null) transactions.close();
    }

    @Test
    void balanceShowsUnavailableInsteadOfAFalseZero() throws Exception {
        PlayerMock player = server.addPlayer("Alice");
        BalanceCommand command = new BalanceCommand(economy, transactions);

        assertTrue(command.onCommand(player, null, "balance", new String[0]));
        awaitAsyncCommandResult();

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));
    }

    @Test
    void leaderboardShowsUnavailableInsteadOfAnEmptyEconomy() throws Exception {
        PlayerMock viewer = server.addPlayer("Viewer");
        TopBalanceCommand command = new TopBalanceCommand(economy, transactions, 10, 100,
                plugin.getPlayerIdentityApi());

        assertTrue(command.onCommand(viewer, null, "topbalance", new String[0]));
        awaitAsyncCommandResult();

        viewer.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));
    }

    @Test
    void bankBalanceShowsUnavailableInsteadOfAFalseZero() throws Exception {
        PlayerMock player = server.addPlayer("Alice");
        BankManager bankManager = new BankManager(storage, economy, plugin, false);
        BankCommand command = new BankCommand(bankManager, economy, plugin.getPlayerIdentityApi());

        assertTrue(command.onCommand(player, null, "bank", new String[]{"balance"}));
        awaitAsyncCommandResult();

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));
    }

    @Test
    void ecoBalanceShowsUnavailableInsteadOfAFalseZero() throws Exception {
        PlayerMock sender = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Target");
        sender.addAttachment(plugin, "sodaeconomy.admin", true);
        EcoCommand command = new EcoCommand(economy, transactions, plugin.getPlayerIdentityApi());

        assertTrue(command.onCommand(sender, null, "eco", new String[]{"balance", target.getName()}));
        awaitAsyncCommandResult();

        sender.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));
    }

    @Test
    void statsAndHistoryReportReadUnavailabilityInsteadOfEmptyData() throws Exception {
        PlayerMock sender = server.addPlayer("Admin");
        sender.addAttachment(plugin, "sodaeconomy.stats", true);
        sender.addAttachment(plugin, "sodaeconomy.history.self", true);
        EcoCommand command = new EcoCommand(economy, transactions, plugin.getPlayerIdentityApi());

        assertTrue(command.onCommand(sender, null, "eco", new String[]{"stats"}));
        awaitAsyncCommandResult();
        sender.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));

        assertTrue(command.onCommand(sender, null, "eco", new String[]{"history"}));
        awaitAsyncCommandResult();
        sender.assertSaid(plugin.getLanguageManager().getFormattedMessage("economy-read-unavailable"));
    }
}
