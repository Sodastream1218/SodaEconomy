package de.sodaeconomy.commands;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.Money;
import de.sodaeconomy.support.MockBukkitTestBase;
import de.sodaeconomy.transaction.TransactionOrigin;
import de.sodaeconomy.transaction.TransactionQuery;
import de.sodaeconomy.transaction.TransactionRecord;
import de.sodaeconomy.transaction.TransactionResult;
import de.sodaeconomy.transaction.TransactionType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcoCommandTest extends MockBukkitTestBase {

    @Test
    void setsBalanceAndNotifiesTheAffectedOnlinePlayer() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Alice");
        admin.setOp(true);

        assertTrue(server.dispatchCommand(admin, "eco set Alice 250"));
        awaitAsyncCommandResult();

        assertEquals(250D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-set-success", "player", "Alice", "amount", plugin.getEconomyManager().formatCurrency(250D)));
        target.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-set-target", "amount", plugin.getEconomyManager().formatCurrency(250D)));
    }

    @Test
    void rejectsInvalidAdminAmountsWithoutMutatingTheTarget() {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Alice");
        admin.setOp(true);
        plugin.getEconomyManager().setBalance(target.getUniqueId(), 100D);

        assertTrue(server.dispatchCommand(admin, "eco give Alice Infinity"));

        assertEquals(100D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("invalid-amount"));
    }

    @Test
    void rejectsCommandExecutionWithoutTheAdminPermission() {
        PlayerMock player = server.addPlayer("Alice");
        EcoCommand command = new EcoCommand(plugin.getEconomyManager());

        assertTrue(command.onCommand(player, null, "eco", new String[]{"stats"}));

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("no-permission"));
    }

    @Test
    void letsAPlayerViewTheirOwnLocalizedTransactionHistory() throws Exception {
        PlayerMock player = server.addPlayer("Alice");
        player.addAttachment(plugin, "sodaeconomy.history.self", true);
        TransactionResult transaction = plugin.getTransactionService().depositAsynchronously(player.getUniqueId(), 25D,
                TransactionType.ADMIN_GIVE, TransactionOrigin.console(), "History test", Map.of("command", "test")).get(5L, TimeUnit.SECONDS);

        assertTrue(transaction.isSuccessful());
        assertTrue(server.dispatchCommand(player, "eco history"));
        awaitLedgerCommand();
        TransactionRecord shownRecord = plugin.getTransactionService().findTransactions(new TransactionQuery(null,
                player.getUniqueId(), null, null, null, null, null, null, 0, 1))
                .get(5L, TimeUnit.SECONDS).records().get(0);

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-history-header", "player", "Alice", "page", 1));
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-history-entry",
                "id", shownRecord.id().toString().substring(0, 8),
                "time", DateTimeFormatter.ISO_INSTANT.format(shownRecord.timestamp()),
                "type", plugin.getLanguageManager().getMessage("transaction-type-" + shownRecord.type().name()
                        .toLowerCase().replace('_', '-')),
                "amount", plugin.getEconomyManager().formatCurrency(Money.fromMinorUnits(shownRecord.appliedAmountMinor())),
                "source", plugin.getLanguageManager().getMessage("transaction-not-applicable"),
                "target", "Alice",
                "status", plugin.getLanguageManager().getMessage("transaction-status-success"),
                "reason", shownRecord.reason()));
    }

    @Test
    void deniesForeignHistoryToAPlayerWithOnlyTheSelfHistoryPermission() {
        PlayerMock player = server.addPlayer("Alice");
        server.addPlayer("Bob");
        player.addAttachment(plugin, "sodaeconomy.history.self", true);

        assertTrue(server.dispatchCommand(player, "eco history Bob"));

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("no-permission"));
    }

    @Test
    void rendersTransactionDetailsThroughTheLanguageSystem() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Alice");
        admin.setOp(true);
        TransactionResult transaction = plugin.getTransactionService().depositAsynchronously(target.getUniqueId(), 15D,
                TransactionType.ADMIN_GIVE, TransactionOrigin.console(), "Detail test", Map.of()).get(5L, TimeUnit.SECONDS);

        assertTrue(transaction.isSuccessful());
        assertTrue(server.dispatchCommand(admin, "eco transaction " + transaction.transaction().id()));
        awaitLedgerCommand();

        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-transaction-header", "id", transaction.transaction().id()));
    }

    @Test
    void createsAnAtomicRollbackThroughTheCommand() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock source = server.addPlayer("Source");
        PlayerMock target = server.addPlayer("Target");
        admin.setOp(true);
        plugin.getTransactionService().getBalanceSynchronously(source.getUniqueId());
        plugin.getTransactionService().getBalanceSynchronously(target.getUniqueId());
        TransactionResult transfer = plugin.getTransactionService().transferAsynchronously(source.getUniqueId(),
                target.getUniqueId(), 50D, TransactionType.PLAYER_TRANSFER, TransactionOrigin.console(),
                "Rollback test", Map.of()).get(5L, TimeUnit.SECONDS);

        assertTrue(transfer.isSuccessful());
        assertTrue(server.dispatchCommand(admin, "eco rollback " + transfer.transaction().id()));
        awaitLedgerCommand();

        assertEquals(1_000D, plugin.getEconomyManager().getBalance(source.getUniqueId()));
        assertEquals(1_000D, plugin.getEconomyManager().getBalance(target.getUniqueId()));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-rollback-success",
                "id", transfer.transaction().id(), "rollbackId", plugin.getTransactionService()
                        .findTransactions(new de.sodaeconomy.transaction.TransactionQuery(null, source.getUniqueId(),
                                target.getUniqueId(), source.getUniqueId(), TransactionType.ROLLBACK, null,
                                null, null, 0, 1)).get(5L, TimeUnit.SECONDS).records().get(0).id()));
    }

    @Test
    void rendersAuditThroughTheExistingLanguageSystem() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Alice");
        admin.setOp(true);
        plugin.getTransactionService().depositAsynchronously(target.getUniqueId(), 10D, TransactionType.ADMIN_GIVE,
                TransactionOrigin.console(), "Audit test", Map.of()).get(5L, TimeUnit.SECONDS);

        assertTrue(server.dispatchCommand(admin, "eco audit Alice"));
        awaitLedgerCommand();
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-audit-header", "player", "Alice"));
    }

    @Test
    void rendersServerStatisticsThroughTheExistingLanguageSystem() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock player = server.addPlayer("Alice");
        admin.setOp(true);
        plugin.getTransactionService().getBalanceSynchronously(player.getUniqueId());

        assertTrue(server.dispatchCommand(admin, "eco stats"));
        awaitLedgerCommand();
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-stats-header"));
    }

    @Test
    void readsPlayerStatisticsWithoutCreatingAnAccount() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        PlayerMock target = server.addPlayer("Alice");
        admin.setOp(true);

        assertTrue(server.dispatchCommand(admin, "eco stats Alice"));
        awaitLedgerCommand();

        assertFalse(plugin.getEconomyManager().getAllBalances().containsKey(target.getUniqueId()));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-stats-player-header", "player", "Alice"));
    }

    @Test
    void offersOnlyPermittedSubcommandsThroughTabCompletion() {
        PlayerMock player = server.addPlayer("Alice");
        player.addAttachment(plugin, "sodaeconomy.history.self", true);
        EcoCommand command = new EcoCommand(plugin.getEconomyManager());

        assertEquals(List.of("history"), command.onTabComplete(player, null, "eco", new String[]{"h"}));
    }

    @Test
    void rendersEveryHelpLineFromTheLanguageSystem() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);

        assertTrue(server.dispatchCommand(admin, "eco"));

        for (String key : new String[]{
                "eco-help-header", "show-history", "show-player-history", "show-transaction", "rollback-transaction",
                "show-audit", "show-stats", "reload-config", "show-version",
                "set-player-balance", "give-player-money", "remove-player-money", "reset-player-balance",
                "show-player-balance", "pay-all", "multi-pay"
        }) {
            admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(key));
        }
    }

    @Test
    void safelyReloadsPrefixCurrencyAndLeaderboardSettings() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("prefix.enabled", true);
        disk.set("prefix.full_prefix", "§d[Reloaded] ");
        disk.set("currency.symbol", " Tokens");
        disk.set("currency.display_after_amount", true);
        disk.set("leaderboard.enabled", true);
        disk.set("leaderboard.top-entries", 2);
        disk.set("leaderboard.max-entries", 4);
        disk.set("balance", 5);
        disk.save(file);

        assertTrue(server.dispatchCommand(admin, "eco reload"));

        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-reload-success", "language", "EN", "topEntries", 2, "maxEntries", 4));
        assertEquals("25.00 Tokens", plugin.getEconomyManager().formatCurrency(25D));
        assertEquals(1_000D, plugin.getEconomyManager().getStartingBalance());
    }

    @Test
    void keepsPreviousSettingsWhenSafeReloadValidationFails() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        String previousCurrency = plugin.getEconomyManager().formatCurrency(25D);
        String previousPrefix = plugin.getLanguageManager().getPrefix();
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration disk = YamlConfiguration.loadConfiguration(file);
        disk.set("prefix.full_prefix", "§4[NotApplied] ");
        disk.set("currency.symbol", " Broken");
        disk.set("leaderboard.top-entries", 10);
        disk.set("leaderboard.max-entries", 2);
        disk.save(file);

        assertTrue(server.dispatchCommand(admin, "eco reload"));

        admin.assertSaid(previousPrefix + plugin.getLanguageManager().getMessage("eco-reload-failed"));
        assertEquals(previousCurrency, plugin.getEconomyManager().formatCurrency(25D));
        assertEquals(previousPrefix, plugin.getLanguageManager().getPrefix());
    }

    @Test
    void requiresTheDedicatedReloadPermission() {
        PlayerMock player = server.addPlayer("Alice");

        assertTrue(server.dispatchCommand(player, "eco reload"));

        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("no-permission"));
    }

    @Test
    void offersReloadThroughTabCompletionToPermittedAdministrators() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "sodaeconomy.admin.reload", true);
        EcoCommand command = new EcoCommand(plugin.getEconomyManager());

        assertEquals(List.of("reload"), command.onTabComplete(admin, null, "eco", new String[]{"rel"}));
    }

    private void awaitLedgerCommand() throws Exception {
        awaitAsyncCommandResult();
    }
}
