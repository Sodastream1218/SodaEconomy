# Database and Network Storage

SodaEconomy supports three storage modes from the same plugin JAR: SQLite, YAML and MYSQL. The
`MYSQL` mode supports both MySQL and MariaDB through MariaDB Connector/J.

## Which backend should I use?

| Backend | Best fit | Recommendation |
| --- | --- | --- |
| SQLite | One Paper/Purpur server | **Recommended default** for most installations |
| YAML | Small/simple local server | Supported when human-readable files are preferred |
| MySQL/MariaDB | Multiple servers or externally managed SQL | Recommended for shared network economy data |

## SQLite

Default configuration:

```yaml
storage:
  type: SQLITE
```

No external database server is required. SodaEconomy creates and manages the local SQLite data under
its plugin data directory. Local persistence uses the asynchronous ordered writer and recovery WAL
when enabled.

Back up the SodaEconomy data directory before major upgrades or storage migrations. Avoid copying a
live SQLite database while the server is actively writing to it unless your backup tooling provides a
consistent database snapshot.

## YAML

```yaml
storage:
  type: YAML
```

YAML is supported and easy to inspect manually, but SQLite is the preferred local backend when player
counts or write volume increase. Do not edit active economy files while the server is running.

## MySQL / MariaDB

```yaml
storage:
  type: MYSQL
  mysql:
    host: localhost
    port: 3306
    database: sodaeconomy
    user: sodaeconomy
    password: "replace-with-a-strong-password"
```

Despite the configuration key `MYSQL`, this mode works with MySQL and MariaDB. Paper loads MariaDB
Connector/J as a plugin library; the database-server driver is not embedded in the SodaEconomy JAR.

### Dedicated database account

Use a dedicated user, not `root`. A typical setup is:

```sql
CREATE DATABASE sodaeconomy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'sodaeconomy'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX
ON sodaeconomy.* TO 'sodaeconomy'@'localhost';
```

Adjust the host part for the real network topology instead of opening access more broadly than
necessary. The schema/migration path needs schema-change privileges while SodaEconomy manages its
schema.

## Multi-server networks

Multiple Paper/Purpur instances may share the same MYSQL database. Each instance must use the same
logical economy configuration (especially starting/maximum balance and banking expectations) and must
point to the same database intentionally.

SodaEconomy's shared JDBC path uses transactional database authority, row locking, runtime maintenance
gates and retry handling for transient MySQL/MariaDB concurrency conflicts. Do not run a
MYSQL→SQLite/YAML migration while another server is still using the shared database.

## Storage migration

Changing `storage.type` is not a live reload. On startup SodaEconomy compares the configured backend
with the last successfully active backend and can migrate the complete supported snapshot when safe.
The target must connect and initialize successfully before data is imported.

A pre-existing target is **not** treated as a merge destination. Plan migration as an administrative
operation, not as a way to combine two economies.

For MYSQL→local migration, `storage.migration.confirm-single-server-mysql` is an explicit safety
acknowledgement. Keep it `false` unless exactly one instance is performing the migration and all other
network servers are stopped.

Use `storage.migration.dry-run: true` to inspect source snapshot counts without importing data or
changing the active-storage marker.

See [Updating & migration](migration.md) for the operational checklist.

## Backups

Create a backup before:

- upgrading across a release with schema/config migration notes;
- changing storage backend;
- changing from single-server to shared-network topology or back;
- database maintenance that can affect tables/users/permissions.

Back up the **authoritative active backend** and keep `config.yml` with the backup so credentials,
storage type and operational context are known.

## Connection problems

If MYSQL fails to start, do not repeatedly change storage types as a workaround. Verify host/port,
database name, user permissions, firewall/TLS settings and database availability. SodaEconomy avoids
silently falling back from a reachable-but-temporarily-unavailable shared database because doing so
could create divergent economy state.

See [Troubleshooting](troubleshooting.md).
