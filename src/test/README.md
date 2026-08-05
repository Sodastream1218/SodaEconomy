# SodaEconomy test suite

Gradle is the canonical test and CI build. It uses Java 21 to run the build while compiling the
plugin for Java 17, which preserves compatibility with the current Paper 1.20.x target.

```powershell
.\gradlew.bat test                  # Unit and MockBukkit tests; no MySQL required
.\gradlew.bat mysqlIntegrationTest  # Only the isolated MySQL integration suite
.\gradlew.bat check                 # Both suites and the critical-logic coverage gate
.\gradlew.bat shadowJar             # Production-ready JAR with bundled SQLite JDBC and an externally loaded MariaDB JDBC driver
```

On Linux and macOS, replace `gradlew.bat` with `./gradlew`. The legacy Maven build remains
available for existing local workflows, but GitHub Actions uses Gradle exclusively.

## Test types and layout

- `de.sodaeconomy` — JUnit business-logic tests for money, economy, and banking.
- `de.sodaeconomy.commands` — MockBukkit command behaviour and permissions.
- `de.sodaeconomy.language` — message and fallback handling.
- `de.sodaeconomy.storage` — configuration tests, per-storage tests, common storage contracts,
  asynchronous write-behind queue tests, and tagged MySQL integration tests.
- `de.sodaeconomy.transaction` — central wallet-ledger, rollback, audit-query, and statistics
  service tests.
- `de.sodaeconomy.integration` — plugin startup and cross-component tests.
- `de.sodaeconomy.support` — MockBukkit lifecycle and in-memory fixtures.

`StorageContractTest` and `WalletTransactionStoreContractTest` are deliberately shared by YAML,
SQLite, and MySQL. Add storage-neutral balance or ledger behaviour there; add database-specific
SQL and connection checks to the MySQL integration tests.
Each MockBukkit test receives a new server and releases it in `MockBukkitTestBase`.

## Isolated MySQL/MariaDB test configuration

The production configuration is `src/main/resources/config.yml`. Shared JDBC integration tests instead
read `src/test/resources/mysql-integration.properties`; that file contains only environment-variable
names, never hostnames or credentials. CI and local development therefore use the same test code
without any possibility of falling back to a production configuration.

Start either a disposable MySQL 8.4 instance or a MariaDB 11.8 LTS instance. MySQL example:

```powershell
docker run --rm --name sodaeconomy-mysql-test `
  -e MYSQL_DATABASE=sodaeconomy_test `
  -e MYSQL_USER=sodaeconomy_test `
  -e MYSQL_PASSWORD=sodaeconomy_test_password `
  -e MYSQL_ROOT_PASSWORD=sodaeconomy_test_root_password `
  -e TZ=UTC -p 3306:3306 mysql:8.4
```

MariaDB example (use only one container on port 3306 at a time):

```powershell
docker run --rm --name sodaeconomy-mariadb-test `
  -e MARIADB_DATABASE=sodaeconomy_test `
  -e MARIADB_USER=sodaeconomy_test `
  -e MARIADB_PASSWORD=sodaeconomy_test_password `
  -e MARIADB_ROOT_PASSWORD=sodaeconomy_test_root_password `
  -e TZ=UTC -p 3306:3306 mariadb:11.8
```

In a second terminal, set the test-only environment values and run the tagged task:

```powershell
$env:SODAECONOMY_TEST_MYSQL = 'true'
$env:SODAECONOMY_TEST_MYSQL_HOST = '127.0.0.1'
$env:SODAECONOMY_TEST_MYSQL_PORT = '3306'
$env:SODAECONOMY_TEST_MYSQL_DATABASE = 'sodaeconomy_test'
$env:SODAECONOMY_TEST_MYSQL_USER = 'sodaeconomy_test'
$env:SODAECONOMY_TEST_MYSQL_PASSWORD = 'sodaeconomy_test_password'
.\gradlew.bat mysqlIntegrationTest
```

The suite intentionally creates, migrates, and removes test rows and may recreate its tables while
testing legacy schema upgrades. Use only a disposable database with the exact dedicated test
credentials above — never a shared, staging, or production database.

## CI pipeline

`.github/workflows/test.yml` runs the same suite once against MySQL 8.4 and once against MariaDB 11.8 LTS. It waits for
the server, configures UTF-8 (`utf8mb4`) and UTC, then runs `test` followed by
`mysqlIntegrationTest`. Credentials are confined to workflow and test-process environment
variables. Docker service containers are discarded with the job, so no data is shared between runs.

The shared JDBC suite covers schema-version migration and idempotence, CRUD including direct read-back,
duplicate keys, null and malformed data, transaction rollback, concurrent transfers, connection
failure, bounded unavailable-endpoint handling, reconnection, and explicit connection closure.
There is no connection pool in the current storage implementation; the tests consequently verify
the single synchronized JDBC connection and its close/reconnect lifecycle.

## Writing tests

Keep tests independent, use Arrange–Act–Assert, and give each test one observable behaviour. Use
JUnit 5 for pure logic and MockBukkit only where Paper behaviour is involved. New MySQL tests must
be tagged `mysql-integration`, guarded by `SODAECONOMY_TEST_MYSQL=true`, use random IDs, and close
every `Connection`, `Statement`, `ResultSet`, executor, and storage instance with
try-with-resources or `finally`.

Critical services and utilities keep an enforced minimum 80% line coverage threshold. Prefer
coverage of money validation, state changes, persistence and error paths over trivial accessors.

## Storage startup and migration safety

`StorageManagerSafetyTest` uses deterministic in-memory storage doubles to verify the startup
state machine without a real database. It covers target initialization before source access,
safe fallback and config persistence for incomplete MySQL settings, no fallback for valid but
unreachable MySQL settings, and restoration of the target snapshot after an interrupted import.
These tests validate orchestration and failure classification; JDBC-specific behaviour remains
covered by the tagged MySQL integration suite.
