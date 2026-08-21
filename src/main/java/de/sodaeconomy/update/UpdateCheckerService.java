package de.sodaeconomy.update;

import de.sodaeconomy.SodaEconomy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Coordinates update checks, caching, channel filtering and reload-safe scheduling. HTTP response
 * work is moved onto SodaEconomy's own update executor before any Bukkit scheduling API is used.
 */
public final class UpdateCheckerService implements AutoCloseable {
    static final long STARTUP_DELAY_SECONDS = 60L;
    private static final Duration FAILURE_LOG_THROTTLE = Duration.ofMinutes(15);

    private final SodaEconomy plugin;
    private final UpdateCheckSource source;
    private final String installedVersion;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final AtomicReference<UpdateCheckerSettings> settings;
    private final AtomicReference<UpdateCheckResult> lastResult;
    private final AtomicLong settingsGeneration = new AtomicLong();
    private final AtomicLong lastFailureLogMillis = new AtomicLong(Long.MIN_VALUE);
    private final CopyOnWriteArrayList<Consumer<UpdateCheckResult>> automaticResultListeners = new CopyOnWriteArrayList<>();

    private CompletableFuture<UpdateCheckResult> inFlight;
    private ScheduledFuture<?> automaticTask;
    private volatile boolean started;
    private volatile boolean closed;

    public UpdateCheckerService(SodaEconomy plugin, UpdateCheckSource source, UpdateCheckerSettings initialSettings) {
        this(plugin, source, initialSettings, Clock.systemUTC(), plugin.getDescription().getVersion());
    }

    UpdateCheckerService(SodaEconomy plugin, UpdateCheckSource source, UpdateCheckerSettings initialSettings,
                         Clock clock, String installedVersion) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.installedVersion = Objects.requireNonNull(installedVersion, "installedVersion");
        this.settings = new AtomicReference<>(Objects.requireNonNull(initialSettings, "initialSettings"));
        this.lastResult = new AtomicReference<>(initialState(initialSettings));
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SodaEconomy-UpdateChecker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Starts scheduling only; no network call is awaited or executed on the plugin-enable thread. */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        rescheduleAutomaticChecks(true);
    }

    /** Applies a previously validated /eco reload snapshot without touching storage or integrations. */
    public synchronized void applySettings(UpdateCheckerSettings newSettings) {
        Objects.requireNonNull(newSettings, "newSettings");
        UpdateCheckerSettings previous = settings.get();
        if (previous.equals(newSettings)) return;

        boolean comparisonChanged = !previous.source().equals(newSettings.source())
                || previous.channel() != newSettings.channel();
        boolean disabled = previous.enabled() && !newSettings.enabled();
        boolean enabled = !previous.enabled() && newSettings.enabled();
        boolean transportChanged = previous.connectTimeoutSeconds() != newSettings.connectTimeoutSeconds()
                || previous.readTimeoutSeconds() != newSettings.readTimeoutSeconds();
        boolean scheduleChanged = previous.checkIntervalHours() != newSettings.checkIntervalHours()
                || previous.checkOnStartup() != newSettings.checkOnStartup()
                || previous.enabled() != newSettings.enabled();

        settings.set(newSettings);

        if (disabled || enabled || comparisonChanged || transportChanged) {
            settingsGeneration.incrementAndGet();
            if (inFlight != null && !inFlight.isDone()) inFlight.cancel(true);
            inFlight = null;
        }

        if (!newSettings.enabled()) {
            lastResult.set(initialState(newSettings));
        } else if (enabled || comparisonChanged) {
            lastResult.set(initialState(newSettings));
        }

        if (started && !closed && (scheduleChanged || comparisonChanged)) {
            boolean promptCheck = enabled || comparisonChanged
                    || (!previous.checkOnStartup() && newSettings.checkOnStartup());
            rescheduleAutomaticChecks(promptCheck);
        }
    }

    public UpdateCheckerSettings settings() {
        return settings.get();
    }

    public UpdateCheckResult lastResult() {
        return lastResult.get();
    }

    /** Uses the cache when it is still fresh for the configured interval. */
    public CompletableFuture<UpdateCheckResult> checkNow() {
        return checkNow(false, false);
    }

    /** Forces a new request unless another check is already in flight. */
    public CompletableFuture<UpdateCheckResult> checkNowForced() {
        return checkNow(true, false);
    }

    private synchronized CompletableFuture<UpdateCheckResult> checkNow(boolean force, boolean automatic) {
        if (closed) return CompletableFuture.completedFuture(
                result(settings.get(), UpdateCheckStatus.CHECK_FAILED, null, null));

        UpdateCheckerSettings snapshot = settings.get();
        if (!snapshot.enabled()) {
            UpdateCheckResult disabled = result(snapshot, UpdateCheckStatus.CHECK_DISABLED, null, null);
            lastResult.set(disabled);
            return CompletableFuture.completedFuture(disabled);
        }

        UpdateVersion installed = UpdateVersion.parse(installedVersion).orElse(null);
        if (installed == null || installed.isDevelopment()) {
            UpdateCheckResult development = result(snapshot, UpdateCheckStatus.DEVELOPMENT_BUILD, null, null);
            lastResult.set(development);
            return CompletableFuture.completedFuture(development);
        }

        UpdateCheckResult cached = lastResult.get();
        if (!force && cacheIsFresh(cached, snapshot.checkInterval())) {
            if (automatic) runOnMainThread(() -> notifyAutomaticListeners(cached));
            return CompletableFuture.completedFuture(cached);
        }
        if (inFlight != null && !inFlight.isDone()) {
            CompletableFuture<UpdateCheckResult> current = inFlight;
            if (automatic) {
                current.thenAcceptAsync(result -> runOnMainThread(() -> notifyAutomaticListeners(result)), executor);
            }
            return current;
        }

        long generation = settingsGeneration.get();
        CompletableFuture<List<UpdateRelease>> sourceFuture = CompletableFuture
                .supplyAsync(() -> source.fetchReleases(snapshot), executor)
                .thenCompose(fetched -> fetched == null
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("Update source returned no completion future"))
                        : fetched);

        CompletableFuture<UpdateCheckResult> evaluatedFuture = sourceFuture
                .handleAsync((releases, throwable) -> {
                    if (throwable != null) {
                        if (generation == settingsGeneration.get()) logFailure(unwrap(throwable));
                        return result(snapshot, UpdateCheckStatus.CHECK_FAILED, null, null);
                    }
                    return evaluate(installed, releases == null ? List.of() : releases, snapshot);
                }, executor);

        /*
         * Publishing is part of the returned completion chain. A caller that observes checkNow()
         * as complete must also be able to observe the cached result. Keeping publication in a
         * detached whenCompleteAsync callback creates a race where an immediate second check can
         * miss the freshly calculated cache entry and issue a duplicate HTTP request.
         */
        CompletableFuture<UpdateCheckResult> future = evaluatedFuture.thenApplyAsync(checkResult -> {
            boolean publish = generation == settingsGeneration.get() && !closed && checkResult != null;
            if (publish) {
                lastResult.set(checkResult);
                if (automatic) runOnMainThread(() -> notifyAutomaticListeners(checkResult));
            }
            return checkResult;
        }, executor);

        inFlight = future;
        future.whenComplete((checkResult, throwable) -> {
            synchronized (UpdateCheckerService.this) {
                if (inFlight == future) inFlight = null;
            }
        });
        return future;
    }

    /**
     * Delivers an async result to a Bukkit consumer without executing Bukkit code on the HTTP
     * completion thread. The transition first runs on the dedicated update executor.
     */
    public void deliverOnMainThread(CompletableFuture<UpdateCheckResult> future, Consumer<UpdateCheckResult> consumer) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(consumer, "consumer");
        future.whenCompleteAsync((result, throwable) -> {
            UpdateCheckResult delivered = throwable == null && result != null
                    ? result
                    : result(settings.get(), UpdateCheckStatus.CHECK_FAILED, null, null);
            runOnMainThread(() -> consumer.accept(delivered));
        }, executor);
    }

    private UpdateCheckResult evaluate(UpdateVersion installed, List<UpdateRelease> releases,
                                       UpdateCheckerSettings snapshot) {
        if (releases.isEmpty()) return result(snapshot, UpdateCheckStatus.NO_RELEASES_FOUND, null, null);

        List<ParsedRelease> allowed = new ArrayList<>();
        int parseableReleaseCount = 0;
        for (UpdateRelease release : releases) {
            UpdateVersion version = UpdateVersion.parse(release.tagName()).orElse(null);
            if (version == null || version.isDevelopment() || version.channel().isEmpty()) continue;
            parseableReleaseCount++;
            UpdateChannel releaseChannel = version.channel().orElseThrow();
            if (release.prerelease() && releaseChannel == UpdateChannel.STABLE) continue;
            if (!snapshot.channel().allows(releaseChannel)) continue;
            allowed.add(new ParsedRelease(version, release));
        }

        if (parseableReleaseCount == 0) {
            return result(snapshot, UpdateCheckStatus.INVALID_REMOTE_VERSION, null, null);
        }
        if (allowed.isEmpty()) return result(snapshot, UpdateCheckStatus.NO_RELEASES_FOUND, null, null);

        ParsedRelease latest = allowed.stream().max(Comparator.comparing(ParsedRelease::version)).orElseThrow();
        UpdateCheckStatus status = latest.version().compareTo(installed) > 0
                ? UpdateCheckStatus.UPDATE_AVAILABLE
                : UpdateCheckStatus.UP_TO_DATE;
        return result(snapshot, status, latest.version(), latest.release().releasePage());
    }

    private boolean cacheIsFresh(UpdateCheckResult cached, Duration interval) {
        if (cached == null || cached.status() == UpdateCheckStatus.NOT_CHECKED
                || cached.status() == UpdateCheckStatus.CHECK_DISABLED
                || cached.status() == UpdateCheckStatus.DEVELOPMENT_BUILD) return false;
        Duration age = Duration.between(cached.checkedAt(), clock.instant());
        if (age.isNegative()) return false;
        Duration freshness = cached.status() == UpdateCheckStatus.CHECK_FAILED ? Duration.ofMinutes(5) : interval;
        return age.compareTo(freshness) < 0;
    }

    private synchronized void rescheduleAutomaticChecks(boolean initialStart) {
        if (automaticTask != null) automaticTask.cancel(false);
        automaticTask = null;
        UpdateCheckerSettings snapshot = settings.get();
        if (!snapshot.enabled() || closed) return;

        long intervalSeconds = TimeUnit.HOURS.toSeconds(snapshot.checkIntervalHours());
        long initialDelay = initialStart && snapshot.checkOnStartup() ? STARTUP_DELAY_SECONDS : intervalSeconds;
        automaticTask = executor.scheduleAtFixedRate(() -> checkNow(false, true),
                initialDelay, intervalSeconds, TimeUnit.SECONDS);
    }

    private void runOnMainThread(Runnable action) {
        if (closed || action == null) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!closed && plugin.isEnabled()) action.run();
            });
        } catch (RuntimeException exception) {
            if (!closed) plugin.getLogger().log(Level.FINE, "[Update] Could not dispatch update callback.", exception);
        }
    }

    public void addAutomaticResultListener(Consumer<UpdateCheckResult> listener) {
        automaticResultListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeAutomaticResultListener(Consumer<UpdateCheckResult> listener) {
        automaticResultListeners.remove(listener);
    }

    private void notifyAutomaticListeners(UpdateCheckResult result) {
        for (Consumer<UpdateCheckResult> listener : automaticResultListeners) listener.accept(result);
    }

    private void logFailure(Throwable cause) {
        long now = clock.millis();
        long previous = lastFailureLogMillis.get();
        if (previous != Long.MIN_VALUE && now - previous < FAILURE_LOG_THROTTLE.toMillis()) return;
        if (!lastFailureLogMillis.compareAndSet(previous, now)) return;

        if (plugin.getConfigManager() != null && plugin.getConfigManager().isIntegrationsDebug()) {
            plugin.getLogger().log(Level.WARNING, "[Update] Update check failed.", cause);
        } else {
            plugin.getLogger().warning("[Update] Update check failed.");
        }
    }

    private UpdateCheckResult initialState(UpdateCheckerSettings settings) {
        return new UpdateCheckResult(settings.enabled() ? UpdateCheckStatus.NOT_CHECKED : UpdateCheckStatus.CHECK_DISABLED,
                installedVersion, null, settings.channel(), settings.source(), null, clock.instant());
    }

    private UpdateCheckResult result(UpdateCheckerSettings snapshot, UpdateCheckStatus status,
                                     UpdateVersion latestVersion, java.net.URI releasePage) {
        return new UpdateCheckResult(status, installedVersion, latestVersion, snapshot.channel(), snapshot.source(),
                releasePage, clock.instant());
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        settingsGeneration.incrementAndGet();
        if (automaticTask != null) automaticTask.cancel(false);
        if (inFlight != null && !inFlight.isDone()) inFlight.cancel(true);
        automaticTask = null;
        inFlight = null;
        automaticResultListeners.clear();
        executor.shutdownNow();
    }

    private record ParsedRelease(UpdateVersion version, UpdateRelease release) {
    }
}
