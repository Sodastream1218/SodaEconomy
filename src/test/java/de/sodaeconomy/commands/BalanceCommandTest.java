package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceCommandTest extends MockBukkitTestBase {

    @Test
    void showsTheConfiguredStartingBalanceToAPlayer() throws Exception {
        PlayerMock player = server.addPlayer("Alice");

        assertTrue(server.dispatchCommand(player, "balance"));
        awaitAsyncCommandResult();

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "balance-display", "balance", plugin.getEconomyManager().formatCurrency(plugin.getEconomyManager().getStartingBalance())));
    }

    @Test
    void rejectsConsoleExecutionWithTheLocalizedMessage() {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        assertTrue(server.dispatchCommand(console, "balance"));

        console.assertSaid(plugin.getLanguageManager().getFormattedMessage("only-players"));
    }
}
