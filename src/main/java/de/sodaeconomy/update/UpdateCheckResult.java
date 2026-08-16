package de.sodaeconomy.update;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable cached view of the last update-check outcome. */
public record UpdateCheckResult(
        UpdateCheckStatus status,
        String installedVersion,
        UpdateVersion latestVersion,
        UpdateChannel channel,
        String source,
        URI releasePage,
        Instant checkedAt
) {
    public UpdateCheckResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(installedVersion, "installedVersion");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    public Optional<UpdateVersion> latest() {
        return Optional.ofNullable(latestVersion);
    }

    public Optional<URI> releasePageOptional() {
        return Optional.ofNullable(releasePage);
    }

    public boolean updateAvailable() {
        return status == UpdateCheckStatus.UPDATE_AVAILABLE && latestVersion != null && releasePage != null;
    }
}
