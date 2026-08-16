package de.sodaeconomy.update;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.commands.EcoCommand;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCommandIntegrationTest extends MockBukkitTestBase {
    @Test
    void versionCommandShowsCachedStateAndRequiresDedicatedPermission() {
        UpdateCheckerService checker = checker(new AtomicInteger());
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        EcoCommand command = new EcoCommand(plugin.getEconomyManager(), plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), checker, notifications);
        PlayerMock player = server.addPlayer("Admin");

        assertTrue(command.onCommand(player, null, "eco", new String[]{"version"}));
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("no-permission"));

        player.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);
        assertTrue(command.onCommand(player, null, "eco", new String[]{"version"}));
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-header"));
        notifications.close();
        checker.close();
    }

    @Test
    void manualVersionCheckIsAsynchronousAndRendersUpdateResult() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService checker = checker(calls);
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        EcoCommand command = new EcoCommand(plugin.getEconomyManager(), plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), checker, notifications);
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);

        assertTrue(command.onCommand(admin, null, "eco", new String[]{"version", "check"}));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-check-started"));
        awaitUpdateCallback();

        assertEquals(1, calls.get());
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-header"));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-installed", "version", "1.0.0"));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-latest", "version", "1.1.0"));
        notifications.close();
        checker.close();
    }


    @Test
    void manualVersionCheckIsRateLimitedGlobally() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService checker = checker(calls);
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        EcoCommand command = new EcoCommand(plugin.getEconomyManager(), plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), checker, notifications);
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);

        assertTrue(command.onCommand(admin, null, "eco", new String[]{"version", "check"}));
        awaitUpdateCallback();
        assertEquals(1, calls.get());

        PlayerMock secondAdmin = server.addPlayer("SecondAdmin");
        secondAdmin.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);
        assertTrue(command.onCommand(secondAdmin, null, "eco", new String[]{"version", "check"}));
        secondAdmin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-version-check-cooldown", "seconds", 60L));
        assertEquals(1, calls.get());

        notifications.close();
        checker.close();
    }

    @Test
    void failedManualCheckShowsFailedStatusInsteadOfThrowing() throws Exception {
        UpdateCheckSource source = new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                return CompletableFuture.failedFuture(new IllegalStateException("offline"));
            }
        };
        UpdateCheckerService checker = new UpdateCheckerService(plugin, source, UpdateCheckerSettings.defaults());
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        EcoCommand command = new EcoCommand(plugin.getEconomyManager(), plugin.getTransactionService(),
                plugin.getPlayerIdentityApi(), checker, notifications);
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);

        assertTrue(command.onCommand(admin, null, "eco", new String[]{"version", "check"}));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-check-started"));
        awaitUpdateCallback();
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-header"));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-version-installed", "version", "1.0.0"));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage(
                "eco-version-latest", "version", plugin.getLanguageManager().getMessage("transaction-not-applicable")));
        admin.assertSaid(plugin.getLanguageManager().getFormattedMessage("eco-version-status", "status",
                plugin.getLanguageManager().getMessage("update-status-check-failed")));
        notifications.close();
        checker.close();
    }

    private void awaitUpdateCallback() throws Exception {
        awaitAsyncCommandResult();
    }

    private UpdateCheckerService checker(AtomicInteger calls) {
        UpdateCheckSource source = new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(List.of(new UpdateRelease("1.1.0", false,
                        URI.create("https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.1.0"))));
            }
        };
        return new UpdateCheckerService(plugin, source, UpdateCheckerSettings.defaults());
    }
}
