package de.sodaeconomy.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds MariaDB Connector/J connection URLs while preserving the documented SodaEconomy MySQL
 * configuration. Legacy MySQL Connector/J option names are translated or deliberately ignored;
 * unknown driver options never reach the connector where they would otherwise be silently ignored.
 */
final class MariaDbJdbcConfiguration {
    static final String DRIVER_CLASS = "org.mariadb.jdbc.Driver";
    static final String CONNECTOR_COORDINATES = "org.mariadb.jdbc:mariadb-java-client:3.5.9";

    private static final Set<String> SSL_MODES = Set.of("disable", "trust", "verify-ca", "verify-full");
    private static final Set<String> NATIVE_PARAMETERS = Set.of(
            "allowLocalInfile",
            "allowPublicKeyRetrieval",
            "cachePrepStmts",
            "connectTimeout",
            "connectionAttributes",
            "connectionCollation",
            "connectionTimeZone",
            "credentialType",
            "defaultFetchSize",
            "disconnectOnExpiredPasswords",
            "dumpQueriesOnException",
            "forceConnectionTimeZoneToSession",
            "initSql",
            "maxAllowedPacket",
            "permitNoResults",
            "prepStmtCacheSize",
            "prepStmtCacheSqlLimit",
            "preserveInstants",
            "rewriteBatchedStatements",
            "serverRsaPublicKeyFile",
            "sessionVariables",
            "socketTimeout",
            "sslMode",
            "tcpKeepAlive",
            "tcpKeepCount",
            "tcpKeepIdle",
            "tcpKeepInterval",
            "tinyInt1isBit",
            "tlsSocketType",
            "trustStore",
            "trustStorePassword",
            "trustStoreType",
            "useBulkStmts",
            "useCompression",
            "useMysqlMetadata",
            "useServerPrepStmts",
            "zeroDateTimeBehavior"
    );

    private MariaDbJdbcConfiguration() {
    }

    static Result create(String host, int port, String database, Map<String, ?> configuredParameters) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(configuredParameters, "configuredParameters");

        Map<String, String> remaining = new LinkedHashMap<>();
        configuredParameters.forEach((key, value) -> {
            if (key != null && value != null) remaining.put(key, String.valueOf(value));
        });

        List<String> warnings = new ArrayList<>();
        Map<String, String> translated = new TreeMap<>();

        String explicitSslMode = remove(remaining, "sslMode");
        String useSsl = remove(remaining, "useSSL");
        String requireSsl = remove(remaining, "requireSSL");
        String verifyServerCertificate = remove(remaining, "verifyServerCertificate");
        if (explicitSslMode != null && !explicitSslMode.isBlank()) {
            String normalized = explicitSslMode.trim().toLowerCase(Locale.ROOT);
            if (SSL_MODES.contains(normalized)) {
                translated.put("sslMode", normalized);
            } else {
                warnings.add("Unsupported MariaDB sslMode '" + explicitSslMode
                        + "' was ignored; valid values are disable, trust, verify-ca and verify-full.");
            }
        } else {
            boolean sslRequested = parseBoolean(useSsl, false) || parseBoolean(requireSsl, false);
            if (sslRequested) {
                translated.put("sslMode", parseBoolean(verifyServerCertificate, false) ? "verify-full" : "trust");
            } else if (useSsl != null || requireSsl != null) {
                translated.put("sslMode", "disable");
            }
        }

        String autoReconnect = remove(remaining, "autoReconnect");
        if (parseBoolean(autoReconnect, false)) {
            warnings.add("storage.mysql.params.autoReconnect is not supported by MariaDB Connector/J 3.x and was "
                    + "ignored because automatic reconnect can make transaction outcomes ambiguous.");
        }

        String serverTimezone = remove(remaining, "serverTimezone");
        if (serverTimezone != null && !serverTimezone.isBlank() && !remaining.containsKey("connectionTimeZone")) {
            translated.put("connectionTimeZone", serverTimezone.trim());
        }

        String characterEncoding = remove(remaining, "characterEncoding");
        if (characterEncoding != null && !isUtfEncoding(characterEncoding)) {
            warnings.add("Legacy characterEncoding='" + characterEncoding
                    + "' was ignored; MariaDB Connector/J negotiates utf8mb4 for SodaEconomy's Unicode schema.");
        }

        String useUnicode = remove(remaining, "useUnicode");
        if (useUnicode != null && !parseBoolean(useUnicode, true)) {
            warnings.add("Legacy useUnicode=false was ignored; SodaEconomy requires Unicode database connections.");
        }

        // MySQL Connector/J aliases that have no safe MariaDB Connector/J 3.x equivalent.
        remove(remaining, "useLegacyDatetimeCode");
        remove(remaining, "useJDBCCompliantTimezoneShift");

        for (Map.Entry<String, String> entry : remaining.entrySet()) {
            if (NATIVE_PARAMETERS.contains(entry.getKey())) {
                translated.put(entry.getKey(), entry.getValue());
            } else {
                warnings.add("Unsupported JDBC parameter '" + entry.getKey()
                        + "' was ignored instead of being silently accepted by the driver.");
            }
        }

        StringBuilder url = new StringBuilder("jdbc:mariadb://")
                .append(host).append(':').append(port).append('/').append(database);
        if (!translated.isEmpty()) {
            url.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : translated.entrySet()) {
                if (!first) url.append('&');
                url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }
        return new Result(url.toString(), translated, warnings);
    }

    private static String remove(Map<String, String> parameters, String key) {
        return parameters.remove(key);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static boolean isUtfEncoding(String value) {
        String normalized = value.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return normalized.equals("utf8") || normalized.equals("utf8mb4");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Result(String jdbcUrl, Map<String, String> parameters, List<String> warnings) {
        Result {
            jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(parameters, "parameters")));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }
}
