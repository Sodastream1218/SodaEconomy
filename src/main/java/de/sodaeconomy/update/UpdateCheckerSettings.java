package de.sodaeconomy.update;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Validated, reload-safe settings for the optional update checker. */
public record UpdateCheckerSettings(
        boolean enabled,
        String source,
        UpdateChannel channel,
        boolean checkOnStartup,
        int checkIntervalHours,
        boolean notifyConsole,
        boolean notifyAdminsOnJoin,
        boolean notifyAdminsPerSession,
        int connectTimeoutSeconds,
        int readTimeoutSeconds
) {
    public static final String GITHUB_SOURCE = "github";
    public static final int MIN_CHECK_INTERVAL_HOURS = 1;
    public static final int MAX_CHECK_INTERVAL_HOURS = 168;
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_CONNECT_TIMEOUT_SECONDS = 30;
    public static final int MAX_READ_TIMEOUT_SECONDS = 60;

    public UpdateCheckerSettings {
        source = Objects.requireNonNull(source, "source").trim().toLowerCase(Locale.ROOT);
        channel = Objects.requireNonNull(channel, "channel");
        if (!GITHUB_SOURCE.equals(source)) {
            throw new IllegalArgumentException("update-checker.source currently supports only github");
        }
        if (checkIntervalHours < MIN_CHECK_INTERVAL_HOURS || checkIntervalHours > MAX_CHECK_INTERVAL_HOURS) {
            throw new IllegalArgumentException("update-checker.check-interval-hours must be between 1 and 168");
        }
        if (connectTimeoutSeconds < MIN_TIMEOUT_SECONDS || connectTimeoutSeconds > MAX_CONNECT_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("update-checker.connect-timeout-seconds must be between 1 and 30");
        }
        if (readTimeoutSeconds < MIN_TIMEOUT_SECONDS || readTimeoutSeconds > MAX_READ_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("update-checker.read-timeout-seconds must be between 1 and 60");
        }
    }

    public static UpdateCheckerSettings defaults() {
        return new UpdateCheckerSettings(true, GITHUB_SOURCE, UpdateChannel.STABLE, true, 12,
                true, true, true, 4, 6);
    }

    public static UpdateCheckerSettings disabledDefaults() {
        UpdateCheckerSettings defaults = defaults();
        return new UpdateCheckerSettings(false, defaults.source(), defaults.channel(), defaults.checkOnStartup(),
                defaults.checkIntervalHours(), defaults.notifyConsole(), defaults.notifyAdminsOnJoin(),
                defaults.notifyAdminsPerSession(), defaults.connectTimeoutSeconds(), defaults.readTimeoutSeconds());
    }

    public Duration checkInterval() {
        return Duration.ofHours(checkIntervalHours);
    }

    public Duration connectTimeout() {
        return Duration.ofSeconds(connectTimeoutSeconds);
    }

    /** Java 17 HttpRequest timeout: maximum time allowed for the HTTP request/response exchange. */
    public Duration requestTimeout() {
        return Duration.ofSeconds(readTimeoutSeconds);
    }
}
