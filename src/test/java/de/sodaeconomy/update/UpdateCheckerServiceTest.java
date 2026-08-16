package de.sodaeconomy.update;

import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerServiceTest extends MockBukkitTestBase {
    private static final URI RELEASE = URI.create("https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.1.0");


    @Test
    void startSchedulesChecksWithoutCallingTheReleaseSourceSynchronously() {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService service = service(source(calls, List.of()), UpdateCheckerSettings.defaults(), "1.0.0");

        service.start();

        assertEquals(0, calls.get(), "Plugin startup must never wait for or immediately perform the GitHub request");
        service.close();
    }

    @Test
    void reportsUpToDateWhenInstalledVersionMatchesLatestAllowedRelease() {
        UpdateCheckerService service = service(source(new AtomicInteger(),
                List.of(new UpdateRelease("1.0.0", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");

        assertEquals(UpdateCheckStatus.UP_TO_DATE, service.checkNowForced().join().status());
        service.close();
    }

    @Test
    void disabledCheckerNeverCallsTheReleaseSource() {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckSource source = source(calls, List.of(new UpdateRelease("1.1.0", false, RELEASE)));
        UpdateCheckerService service = service(source, UpdateCheckerSettings.disabledDefaults(), "1.0.0");

        assertEquals(UpdateCheckStatus.CHECK_DISABLED, service.checkNowForced().join().status());
        assertEquals(0, calls.get());
        service.close();
    }

    @Test
    void developmentBuildSkipsRemoteComparison() {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService service = service(source(calls, List.of()), UpdateCheckerSettings.defaults(),
                "1.0.0-SNAPSHOT");

        assertEquals(UpdateCheckStatus.DEVELOPMENT_BUILD, service.checkNowForced().join().status());
        assertEquals(0, calls.get());
        service.close();
    }


    @Test
    void coalescesConcurrentChecksIntoOneInFlightRequest() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<List<UpdateRelease>> pending = new CompletableFuture<>();
        UpdateCheckSource source = new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                calls.incrementAndGet();
                return pending;
            }
        };
        UpdateCheckerService service = service(source, UpdateCheckerSettings.defaults(), "1.0.0");

        CompletableFuture<UpdateCheckResult> first = service.checkNowForced();
        CompletableFuture<UpdateCheckResult> second = service.checkNowForced();
        assertSame(first, second);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);
        while (calls.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(1, calls.get());

        pending.complete(List.of(new UpdateRelease("1.1.0", false, RELEASE)));
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, first.join().status());
        service.close();
    }

    @Test
    void synchronousSourceFailureIsContainedAsCheckFailure() {
        UpdateCheckSource source = new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                throw new IllegalStateException("synthetic synchronous failure");
            }
        };
        UpdateCheckerService service = service(source, UpdateCheckerSettings.defaults(), "1.0.0");

        assertEquals(UpdateCheckStatus.CHECK_FAILED, service.checkNowForced().join().status());
        service.close();
    }

    @Test
    void reportsNetworkOrParserFailuresWithoutThrowingToThePlugin() {
        UpdateCheckSource failing = new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                return CompletableFuture.failedFuture(new IllegalStateException("synthetic failure"));
            }
        };
        UpdateCheckerService service = service(failing, UpdateCheckerSettings.defaults(), "1.0.0");
        assertEquals(UpdateCheckStatus.CHECK_FAILED, service.checkNowForced().join().status());
        service.close();
    }

    @Test
    void reportsNoReleasesAndInvalidRemoteVersionsDistinctly() {
        UpdateCheckerService empty = service(source(new AtomicInteger(), List.of()),
                UpdateCheckerSettings.defaults(), "1.0.0");
        assertEquals(UpdateCheckStatus.NO_RELEASES_FOUND, empty.checkNowForced().join().status());

        UpdateCheckerService invalid = service(source(new AtomicInteger(), List.of(
                new UpdateRelease("nightly-main", true, RELEASE),
                new UpdateRelease("1.2", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");
        assertEquals(UpdateCheckStatus.INVALID_REMOTE_VERSION, invalid.checkNowForced().join().status());
        empty.close();
        invalid.close();
    }

    @Test
    void respectsConfiguredPrereleaseChannel() {
        List<UpdateRelease> releases = List.of(
                new UpdateRelease("1.1.0-alpha.2", true, RELEASE),
                new UpdateRelease("1.0.1", false, RELEASE));

        UpdateCheckerSettings stable = settings(UpdateChannel.STABLE);
        UpdateCheckerService stableService = service(source(new AtomicInteger(), releases), stable, "1.0.0");
        UpdateCheckResult stableResult = stableService.checkNowForced().join();
        assertEquals("1.0.1", stableResult.latest().orElseThrow().display());

        UpdateCheckerSettings alpha = settings(UpdateChannel.ALPHA);
        UpdateCheckerService alphaService = service(source(new AtomicInteger(), releases), alpha, "1.0.0");
        UpdateCheckResult alphaResult = alphaService.checkNowForced().join();
        assertEquals("1.1.0-alpha.2", alphaResult.latest().orElseThrow().display());
        stableService.close();
        alphaService.close();
    }

    @Test
    void cachesSuccessfulResultsInsteadOfRepeatingRequests() {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService service = service(source(calls,
                List.of(new UpdateRelease("1.1.0", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");

        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, service.checkNow().join().status());
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, service.checkNow().join().status());
        assertEquals(1, calls.get());
        service.close();
    }


    @Test
    void notificationOnlyReloadKeepsTheLastValidCachedResult() {
        UpdateCheckerService service = service(source(new AtomicInteger(),
                List.of(new UpdateRelease("1.1.0", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, service.checkNowForced().join().status());

        UpdateCheckerSettings current = service.settings();
        UpdateCheckerSettings notificationsOff = new UpdateCheckerSettings(true, current.source(), current.channel(),
                current.checkOnStartup(), current.checkIntervalHours(), false, false, current.notifyAdminsPerSession(),
                current.connectTimeoutSeconds(), current.readTimeoutSeconds());
        service.applySettings(notificationsOff);

        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, service.lastResult().status());
        service.close();
    }

    @Test
    void channelReloadInvalidatesAResultCalculatedForTheOldChannel() {
        UpdateCheckerService service = service(source(new AtomicInteger(),
                List.of(new UpdateRelease("1.1.0", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");
        assertEquals(UpdateCheckStatus.UPDATE_AVAILABLE, service.checkNowForced().join().status());

        service.applySettings(settings(UpdateChannel.BETA));

        assertEquals(UpdateCheckStatus.NOT_CHECKED, service.lastResult().status());
        assertEquals(UpdateChannel.BETA, service.lastResult().channel());
        service.close();
    }

    @Test
    void reloadDisablesFutureAutomaticChecksImmediately() {
        AtomicInteger calls = new AtomicInteger();
        UpdateCheckerService service = service(source(calls,
                List.of(new UpdateRelease("1.1.0", false, RELEASE))), UpdateCheckerSettings.defaults(), "1.0.0");
        service.start();
        service.applySettings(UpdateCheckerSettings.disabledDefaults());

        assertEquals(UpdateCheckStatus.CHECK_DISABLED, service.lastResult().status());
        assertFalse(service.settings().enabled());
        service.close();
    }

    private UpdateCheckerService service(UpdateCheckSource source, UpdateCheckerSettings settings, String installed) {
        return new UpdateCheckerService(plugin, source, settings,
                Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC), installed);
    }

    private static UpdateCheckSource source(AtomicInteger calls, List<UpdateRelease> releases) {
        return new UpdateCheckSource() {
            @Override public String id() { return "github"; }
            @Override public CompletableFuture<List<UpdateRelease>> fetchReleases(UpdateCheckerSettings settings) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(releases);
            }
        };
    }

    private static UpdateCheckerSettings settings(UpdateChannel channel) {
        UpdateCheckerSettings defaults = UpdateCheckerSettings.defaults();
        return new UpdateCheckerSettings(true, "github", channel, defaults.checkOnStartup(),
                defaults.checkIntervalHours(), defaults.notifyConsole(), defaults.notifyAdminsOnJoin(),
                defaults.notifyAdminsPerSession(), defaults.connectTimeoutSeconds(), defaults.readTimeoutSeconds());
    }
}
