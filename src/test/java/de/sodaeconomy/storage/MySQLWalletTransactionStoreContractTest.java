package de.sodaeconomy.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Tag("mysql-integration")
@ResourceLock("mysql-integration")
@EnabledIfEnvironmentVariable(named = "SODAECONOMY_TEST_MYSQL", matches = "true")
class MySQLWalletTransactionStoreContractTest extends WalletTransactionStoreContractTest {
    @Override
    protected void configureStorage() {
        MySqlTestEnvironment environment = MySqlTestEnvironment.load();
        environment.applyTo(plugin);
        resetSchema(environment);
    }

    @Override
    protected Storage createStorage() {
        return new MySQLStorage();
    }

    private static void resetSchema(MySqlTestEnvironment environment) {
        try (Connection connection = environment.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS wallet_transactions");
            statement.executeUpdate("DROP TABLE IF EXISTS player_identities");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_runtime_state");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_scheduled_jobs");
            statement.executeUpdate("DROP TABLE IF EXISTS sodaeconomy_schema_version");
            statement.executeUpdate("DROP TABLE IF EXISTS balances");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not reset the MySQL wallet transaction contract test schema", exception);
        }
    }
}
