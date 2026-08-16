package de.sodaeconomy.update;

import be.seeseemelk.mockbukkit.entity.PlayerMock;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class UpdateNotificationServiceTest extends MockBukkitTestBase {
    @Test
    void requiresDedicatedPermissionAndNotifiesOnlyOncePerSession() throws Exception {
        UpdateCheckerService checker = checker(UpdateCheckerSettings.defaults());
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        PlayerMock player = server.addPlayer("Admin");
        UpdateCheckResult result = availableResult();

        notifications.notifyPlayerIfEligible(player, result);
        assertTrue(notifiedSessions(notifications).isEmpty());

        player.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);
        notifications.notifyPlayerIfEligible(player, result);
        player.assertSaid(plugin.getLanguageManager().getFormattedMessage("update-available",
                "latest", "1.1.0", "installed", "1.0.0"));
        assertEquals(Set.of(player.getUniqueId()), notifiedSessions(notifications));

        notifications.notifyPlayerIfEligible(player, result);
        assertEquals(1, notifiedSessions(notifications).size());

        notifications.close();
        checker.close();
    }

    @Test
    void doesNotNotifyWhenNoUpdateIsAvailable() throws Exception {
        UpdateCheckerService checker = checker(UpdateCheckerSettings.defaults());
        UpdateNotificationService notifications = new UpdateNotificationService(plugin, checker,
                plugin.getLanguageManager());
        PlayerMock player = server.addPlayer("Admin");
        player.addAttachment(plugin, UpdateNotificationService.UPDATE_PERMISSION, true);
        UpdateCheckResult result = new UpdateCheckResult(UpdateCheckStatus.UP_TO_DATE, "1.0.0",
                UpdateVersion.parse("1.0.0").orElseThrow(), UpdateChannel.STABLE, "github",
                URI.create("https://example.invalid/release"), Instant.now());

        notifications.notifyPlayerIfEligible(player, result);
        assertTrue(notifiedSessions(notifications).isEmpty());
        notifications.close();
        checker.close();
    }

    private UpdateCheckerService checker(UpdateCheckerSettings settings) {
        return new UpdateCheckerService(plugin, new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<java.util.List<UpdateRelease>> fetchReleases(UpdateCheckerSettings ignored) {
                return CompletableFuture.completedFuture(java.util.List.of());
            }
        }, settings);
    }

    private static UpdateCheckResult availableResult() {
        return new UpdateCheckResult(UpdateCheckStatus.UPDATE_AVAILABLE, "1.0.0",
                UpdateVersion.parse("1.1.0").orElseThrow(), UpdateChannel.STABLE, "github",
                URI.create("https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.1.0"), Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static Set<UUID> notifiedSessions(UpdateNotificationService notifications) throws Exception {
        Field field = UpdateNotificationService.class.getDeclaredField("notifiedSessions");
        field.setAccessible(true);
        return Set.copyOf((Set<UUID>) field.get(notifications));
    }
}
