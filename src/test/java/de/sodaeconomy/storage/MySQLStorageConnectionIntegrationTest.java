package de.sodaeconomy.storage;

import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Connection failure and recovery tests against either supported database service container. */
@Tag("mysql-integration")
@ResourceLock("mysql-integration")
@EnabledIfEnvironmentVariable(named = "SODAECONOMY_TEST_MYSQL", matches = "true")
class MySQLStorageConnectionIntegrationTest extends MockBukkitTestBase {
    private MySqlTestEnvironment mysql;

    @org.junit.jupiter.api.BeforeEach
    void configureMySql() {
        mysql = MySqlTestEnvironment.load();
        mysql.applyTo(plugin);
    }


    @Test
    void usesMariaDbConnectorJForBothSupportedDatabaseProducts() throws Exception {
        Class.forName(MariaDbJdbcConfiguration.DRIVER_CLASS);
        try (Connection connection = mysql.openConnection()) {
            assertTrue(connection.getMetaData().getDriverName().toLowerCase().contains("mariadb"));
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mariadb:"));
        }
    }

    @Test
    void opensAndClosesItsJdbcConnection() throws Exception {
        MySQLStorage storage = new MySQLStorage();
        try {
            storage.init(plugin);

            assertTrue(storage.isConnected());
            storage.close();
            assertFalse(storage.isConnected());
        } finally {
            storage.close();
        }
    }

    @Test
    void reconnectsAfterThePreviousConnectionWasClosed() throws Exception {
        UUID playerId = UUID.randomUUID();
        MySQLStorage storage = new MySQLStorage();
        try {
            storage.init(plugin);
            storage.getOrCreateBalance(playerId, 42D);
            storage.close();

            assertEquals(42D, storage.getBalance(playerId));
            assertTrue(storage.isConnected());
        } finally {
            storage.close();
            deleteAccount(playerId);
        }
    }

    @Test
    void rejectsInvalidCredentials() {
        MySQLStorage storage = new MySQLStorage();
        mysql.withCredentials("invalid_sodaeconomy_user", "invalid_sodaeconomy_password").applyTo(plugin);
        try {
            assertThrows(SQLException.class, () -> storage.init(plugin));
            assertFalse(storage.isConnected());
        } finally {
            storage.close();
        }
    }

    @Test
    void failsPromptlyWhenTheDatabaseEndpointIsUnavailable() {
        MySQLStorage storage = new MySQLStorage();
        mysql.withEndpoint("127.0.0.1", 1, 500).applyTo(plugin);
        try {
            assertTimeout(Duration.ofSeconds(5), () ->
                    assertThrows(SQLException.class, () -> storage.init(plugin)));
            assertFalse(storage.isConnected());
        } finally {
            storage.close();
        }
    }

    @Test
    void respectsTheSocketTimeoutWhenTheMySqlHandshakeStalls() throws Exception {
        try (ServerSocket stalledEndpoint = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch acceptedConnection = new CountDownLatch(1);
            Future<?> acceptedSocket = executor.submit(() -> {
                try (java.net.Socket ignored = stalledEndpoint.accept()) {
                    acceptedConnection.countDown();
                    Thread.sleep(1_000L);
                }
                return null;
            });
            MySQLStorage storage = new MySQLStorage();
            mysql.withEndpoint("127.0.0.1", stalledEndpoint.getLocalPort(), 500)
                    .withSocketTimeout(250)
                    .applyTo(plugin);
            try {
                assertTimeout(Duration.ofSeconds(3), () ->
                        assertThrows(SQLException.class, () -> storage.init(plugin)));
                assertTrue(acceptedConnection.await(1, TimeUnit.SECONDS));
            } finally {
                storage.close();
                acceptedSocket.get(2, TimeUnit.SECONDS);
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
    }

    private void deleteAccount(UUID playerId) throws SQLException {
        try (java.sql.Connection connection = mysql.openConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("DELETE FROM balances WHERE uuid=?")) {
            statement.setString(1, playerId.toString());
            statement.executeUpdate();
        }
    }
}
