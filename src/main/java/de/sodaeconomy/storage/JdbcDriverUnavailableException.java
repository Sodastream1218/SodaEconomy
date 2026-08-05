package de.sodaeconomy.storage;

import java.sql.SQLException;

/** Signals that Paper did not make the configured external JDBC driver available to the plugin. */
final class JdbcDriverUnavailableException extends SQLException {
    private static final long serialVersionUID = 1L;

    JdbcDriverUnavailableException(String message, Throwable cause) {
        super(message, "08001", cause);
    }
}
