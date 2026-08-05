package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.command.ConsoleCommandSenderMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayCommandTest extends MockBukkitTestBase {

    @Test
    void transfersTheFullAmountAndNotifiesBothPlayers() throws Exception {
        PlayerMock sender = server.addPlayer("Alice");
        PlayerMock target = server.addPlayer("Bob");
        plugin.getEconomyManager().setBalance(sender.getUniqueId(), 100D);
        plugin.getEconomyManager().setBalance(target.getUniqueId(), 0D);

        assertTrue(server.dispatchCommand(sender, "pay Bob 25"));
        awaitAsyncCommandResult();

        assertEquals(75D, plugin.getEconomyManager().getBalance(sender.getUniqueId()));
        assertEquals(25D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        sender.assertSaid(plugin.getLanguageManager().getPrefix() + plugin.getLanguageManager().getMessage(
                "pay-success-sender", "player", "Bob", "amount", plugin.getEconomyManager().formatCurrency(25D)));
        target.assertSaid(plugin.getLanguageManager().getPrefix() + plugin.getLanguageManager().getMessage(
                "pay-success-target", "player", "Alice", "amount", plugin.getEconomyManager().formatCurrency(25D)));
    }

    @Test
    void rejectsNonFiniteAmountsWithoutChangingBalances() {
        PlayerMock sender = server.addPlayer("Alice");
        PlayerMock target = server.addPlayer("Bob");
        plugin.getEconomyManager().setBalance(sender.getUniqueId(), 100D);
        plugin.getEconomyManager().setBalance(target.getUniqueId(), 0D);

        assertTrue(server.dispatchCommand(sender, "pay Bob NaN"));

        assertEquals(100D, plugin.getEconomyManager().getBalance(sender.getUniqueId()));
        assertEquals(0D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        sender.assertSaid(plugin.getLanguageManager().getPrefix() + plugin.getLanguageManager().getMessage("invalid-amount"));
    }

    @Test
    void rejectsPaymentToAnAccountAtItsMaximumWithoutDestroyingMoney() throws Exception {
        PlayerMock sender = server.addPlayer("Alice");
        PlayerMock target = server.addPlayer("Bob");
        plugin.getEconomyManager().setBalance(sender.getUniqueId(), 100D);
        plugin.getEconomyManager().setBalance(target.getUniqueId(), plugin.getEconomyManager().getMaxBalance() - 5D);

        assertTrue(server.dispatchCommand(sender, "pay Bob 10"));
        awaitAsyncCommandResult();

        assertEquals(100D, plugin.getEconomyManager().getBalance(sender.getUniqueId()));
        assertEquals(plugin.getEconomyManager().getMaxBalance() - 5D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        sender.assertSaid(plugin.getLanguageManager().getPrefix() + plugin.getLanguageManager().getMessage("target-max-balance", "player", "Bob"));
    }

    @Test
    void rejectsConsoleExecution() {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        assertTrue(server.dispatchCommand(console, "pay Alice 1"));

        console.assertSaid(plugin.getLanguageManager().getPrefix() + plugin.getLanguageManager().getMessage("only-players"));
    }
}
