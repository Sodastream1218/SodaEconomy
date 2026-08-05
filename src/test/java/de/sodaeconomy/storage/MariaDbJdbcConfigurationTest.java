package de.sodaeconomy.storage;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbJdbcConfigurationTest {

    @Test
    void translatesTheDocumentedLegacyMySqlParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("useSSL", false);
        parameters.put("autoReconnect", false);
        parameters.put("allowPublicKeyRetrieval", true);
        parameters.put("connectTimeout", 5_000);
        parameters.put("socketTimeout", 30_000);
        parameters.put("tcpKeepAlive", true);
        parameters.put("serverTimezone", "UTC");
        parameters.put("characterEncoding", "UTF-8");

        MariaDbJdbcConfiguration.Result result = MariaDbJdbcConfiguration.create(
                "127.0.0.1", 3306, "sodaeconomy", parameters);

        assertTrue(result.jdbcUrl().startsWith("jdbc:mariadb://127.0.0.1:3306/sodaeconomy?"));
        assertEquals("disable", result.parameters().get("sslMode"));
        assertEquals("UTC", result.parameters().get("connectionTimeZone"));
        assertEquals("true", result.parameters().get("allowPublicKeyRetrieval"));
        assertFalse(result.jdbcUrl().contains("jdbc:mysql:"));
        assertFalse(result.jdbcUrl().contains("useSSL"));
        assertFalse(result.jdbcUrl().contains("autoReconnect"));
        assertFalse(result.jdbcUrl().contains("serverTimezone"));
        assertFalse(result.jdbcUrl().contains("characterEncoding"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void mapsLegacyTlsVerificationToMariaDbSslMode() {
        MariaDbJdbcConfiguration.Result result = MariaDbJdbcConfiguration.create(
                "database.example", 3306, "economy",
                Map.of("useSSL", true, "verifyServerCertificate", true));

        assertEquals("verify-full", result.parameters().get("sslMode"));
    }

    @Test
    void explicitNativeSslModeTakesPrecedence() {
        MariaDbJdbcConfiguration.Result result = MariaDbJdbcConfiguration.create(
                "database.example", 3306, "economy",
                Map.of("useSSL", false, "sslMode", "VERIFY-CA"));

        assertEquals("verify-ca", result.parameters().get("sslMode"));
    }

    @Test
    void ignoresUnsafeReconnectAndUnknownParametersWithWarnings() {
        MariaDbJdbcConfiguration.Result result = MariaDbJdbcConfiguration.create(
                "localhost", 3306, "economy",
                Map.of("autoReconnect", true, "madeUpMySqlOption", "value"));

        assertFalse(result.parameters().containsKey("autoReconnect"));
        assertFalse(result.parameters().containsKey("madeUpMySqlOption"));
        assertEquals(2, result.warnings().size());
    }

    @Test
    void preservesSupportedMariaDbNativeParameters() {
        MariaDbJdbcConfiguration.Result result = MariaDbJdbcConfiguration.create(
                "localhost", 3306, "economy",
                Map.of("rewriteBatchedStatements", true, "useServerPrepStmts", false,
                        "connectionCollation", "utf8mb4_unicode_ci"));

        assertEquals("true", result.parameters().get("rewriteBatchedStatements"));
        assertEquals("false", result.parameters().get("useServerPrepStmts"));
        assertEquals("utf8mb4_unicode_ci", result.parameters().get("connectionCollation"));
    }
}
