package de.sodaeconomy.storage;

/** A classified storage-startup failure with an administrator-friendly explanation. */
public class StorageStartupException extends Exception {
    private static final long serialVersionUID = 1L;


    public enum Kind {
        INVALID_CONFIGURATION,
        TARGET_UNAVAILABLE,
        MIGRATION_FAILED,
        STATE_PERSISTENCE_FAILED
    }

    private final Kind kind;

    public StorageStartupException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public StorageStartupException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
