package de.sodaeconomy.update;

/** Outcome of an update check or of the current update-checker state. */
public enum UpdateCheckStatus {
    NOT_CHECKED,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DEVELOPMENT_BUILD,
    CHECK_DISABLED,
    CHECK_FAILED,
    NO_RELEASES_FOUND,
    INVALID_REMOTE_VERSION
}
