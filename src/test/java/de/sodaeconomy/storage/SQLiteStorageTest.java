package de.sodaeconomy.storage;

import de.sodaeconomy.identity.PlayerIdentity;
import de.sodaeconomy.identity.PlayerType;
import de.sodaeconomy.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteStorageTest extends MockBukkitTestBase {

    @Test
    void persistsAndTransfersBalancesTransactionally() throws Exception {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        SQLiteStorage storage = new SQLiteStorage();
        SQLiteStorage reloadedStorage = null;

        try {
            storage.init(plugin);

            assertNull(storage.getBalance(source));
            storage.getOrCreateBalance(source, 100D);
            storage.getOrCreateBalance(target, 50D);
            assertTrue(storage.transferMain(source, target, 25D));
            storage.close();

            reloadedStorage = new SQLiteStorage();
            reloadedStorage.init(plugin);

            assertEquals(75D, reloadedStorage.getBalance(source));
            assertEquals(75D, reloadedStorage.getBalance(target));
        } finally {
            storage.close();
            if (reloadedStorage != null) reloadedStorage.close();
        }
    }
    @Test
    void migratesSchemaThreeToTheIdentityRegistryWithoutChangingBalances() throws Exception {
        UUID playerId = UUID.randomUUID();
        SQLiteStorage initial = new SQLiteStorage();
        SQLiteStorage migrated = null;
        File database = new File(new File(plugin.getDataFolder(), "storage"), "economy.db");

        try {
            initial.init(plugin);
            initial.getOrCreateBalance(playerId, 42D);
            initial.close();

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
                statement.executeUpdate("UPDATE sodaeconomy_schema_version SET version=3 "
                        + "WHERE schema_name='sodaeconomy'");
            }

            migrated = new SQLiteStorage();
            migrated.init(plugin);

            assertEquals(42D, migrated.getBalance(playerId));
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.getAbsolutePath());
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT version FROM sodaeconomy_schema_version "
                         + "WHERE schema_name='sodaeconomy'")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
            assertTrue(migrated.getAllPlayerIdentities().isEmpty());
        } finally {
            initial.close();
            if (migrated != null) migrated.close();
        }
    }

    @Test
    void persistsPlayerIdentityAcrossReinitialization() throws Exception {
        UUID playerId = UUID.randomUUID();
        PlayerIdentity identity = PlayerIdentity.of(playerId, ".BedrockUser", Instant.ofEpochMilli(1234),
                PlayerType.BEDROCK);
        SQLiteStorage storage = new SQLiteStorage();
        SQLiteStorage reloaded = null;

        try {
            storage.init(plugin);
            storage.upsertPlayerIdentity(identity);
            storage.close();

            reloaded = new SQLiteStorage();
            reloaded.init(plugin);
            assertEquals(identity, reloaded.findPlayerIdentity(playerId).orElseThrow());
        } finally {
            storage.close();
            if (reloaded != null) reloaded.close();
        }
    }

}
