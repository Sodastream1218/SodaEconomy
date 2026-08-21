# JDBC Driver Migration: MariaDB Connector/J

## Scope

SodaEconomy's storage type remains `MYSQL`. Existing configuration keys, databases, schemas,
wallets, bank accounts, journal entries, identities, migrations, locks and transaction semantics are
unchanged. Only the JDBC client implementation changes from MySQL Connector/J to MariaDB
Connector/J.

## Selected driver

- Coordinates: `org.mariadb.jdbc:mariadb-java-client:3.5.9`
- Driver class: `org.mariadb.jdbc.Driver`
- JDBC scheme: `jdbc:mariadb://`
- Runtime delivery: Paper `plugin.yml` libraries
- Test delivery: Maven `test` scope and Gradle `testRuntimeOnly`

MariaDB Connector/J supports both MariaDB and MySQL servers. The driver is deliberately not shaded
into SodaEconomy.

## Backward-compatible configuration

The public configuration remains under `storage.mysql` and the storage enum remains `MYSQL`.
Documented legacy parameters are translated as follows:

| Existing key | MariaDB Connector/J behavior |
| --- | --- |
| `useSSL=false` | `sslMode=disable` |
| `useSSL=true` | `sslMode=trust`, unless certificate verification is explicitly requested |
| `verifyServerCertificate=true` | `sslMode=verify-full` |
| `serverTimezone` | `connectionTimeZone` |
| `allowPublicKeyRetrieval` | passed through |
| `connectTimeout` | passed through in milliseconds |
| `socketTimeout` | passed through in milliseconds |
| `tcpKeepAlive` | passed through |
| `autoReconnect` | ignored; automatic reconnect can hide an unknown transaction outcome |
| `characterEncoding=UTF-8` | omitted; the driver negotiates Unicode/utf8mb4 |

An explicit native `sslMode` takes precedence. Unsupported options are logged and ignored rather
than silently accepted by the driver.

## Database account and privileges

Use a dedicated database account for SodaEconomy. Do **not** use a MySQL/MariaDB `root` or other
server-wide administrative account in `config.yml`.

For the current schema and automatic migrations, the dedicated account needs normal DML access plus
schema-local DDL privileges on the SodaEconomy database: `SELECT`, `INSERT`, `UPDATE`, `DELETE`,
`CREATE`, `ALTER`, and `INDEX`. It does not need global administrator privileges.

Example for a database hosted on the same machine:

```sql
CREATE USER 'sodaeconomy'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
ON `sodaeconomy`.* TO 'sodaeconomy'@'localhost';
```

Adapt the host part to the actual network topology instead of granting unnecessarily broad access.
Future releases that require additional migration privileges must document that requirement before
operators upgrade.

## Data and SQL compatibility

No schema version was added. All existing DDL, migrations, `SELECT ... FOR UPDATE`, named locks,
maintenance gates, idempotency constraints, journal writes and rollback paths remain unchanged.
Deadlock retries continue to use SQLState `40001`/`41000` and vendor codes `1213`/`1205`, not
exception message text.

## Testing

The same tagged JDBC integration suite runs against:

- MySQL 8.4
- MariaDB 11.8 LTS

It covers schema creation/upgrades, wallet and bank operations, journal/audit/statistics, identities,
Vault-style transactions, rollback, concurrent transactions, independent server instances,
connection failure and reconnect behavior.

## Paper library availability

Paper resolves `plugin.yml` libraries before enabling the plugin. A clean server therefore needs
network access to Paper's configured Maven Central mirror on the first start. Once cached, the same
library is reused. If resolution fails before plugin startup, Paper reports the library-loading
failure; SodaEconomy cannot switch storage because its lifecycle has not begun. If the class is
missing despite plugin loading and MYSQL is selected, SodaEconomy reports the exact missing
coordinates and refuses to activate the MYSQL backend. YAML and SQLite remain functionally
independent when the library has been resolved normally.
