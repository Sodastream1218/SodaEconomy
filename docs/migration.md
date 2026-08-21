# Updating and Migration

Treat economy upgrades as data changes even when a release is expected to migrate automatically.
The safe default is: **back up first, update second, verify third**.

## Updating SodaEconomy

1. Read the release notes and `CHANGELOG.md`.
2. Back up the active SodaEconomy data and `config.yml`.
3. Stop every server instance that shares the same economy when the release notes require database
   migration or maintenance exclusivity.
4. Replace the old plugin JAR with the new official release JAR.
5. Start one server and watch the complete SodaEconomy startup log.
6. Verify `/balance`, `/pay`, `/topbalance` and any enabled banking/integrations.
7. For shared SQL networks, bring the remaining instances online only after the first startup and any
   schema migration have completed successfully.

Never keep two SodaEconomy versions active against the same shared database during a coordinated
upgrade unless the release notes explicitly say that mixed-version operation is supported.

## Configuration updates

SodaEconomy provides defaults for newly introduced options. Compare your generated/default config with
your existing `config.yml` after an upgrade and read release notes for new startup-only settings.

Use `/eco reload` only for the documented reload-safe subset. Storage, banking, persistence and Vault
lifecycle changes require restart.

## Storage backend migration

Before changing `storage.type`:

1. Back up the source backend.
2. Ensure the target backend is empty/appropriate for the migration and fully reachable.
3. For MYSQL, verify the dedicated account and schema permissions.
4. For MYSQL→local, stop every other server using the SQL economy and set
   `storage.migration.confirm-single-server-mysql: true` only for the controlled migration startup.
5. Optionally use `storage.migration.dry-run: true` first to inspect the source snapshot.
6. Change `storage.type` and restart.
7. Verify balances, banks, identity data and transaction history before reopening the server/network.

Do not edit `last-storage-type.txt` manually. It records the last successfully active storage and is a
safety input to migration decisions.

## Schema migrations

SQLite and MYSQL/MariaDB storage versions are managed by SodaEconomy. A release may perform an
internal schema migration during startup. Do not interrupt the process deliberately, and always keep a
backup that predates the upgrade.

Downgrades are **not** generally promised to reverse newer schema/config changes. Restore a compatible
backup if a release must be rolled back and the release notes do not explicitly document downgrade
support.

## Local recovery files

YAML/SQLite installations may contain SodaEconomy's recovery WAL when accepted local writes are still
unresolved. Do not delete recovery files to silence a startup warning. Follow the recovery guidance in
[Local persistence recovery](local-persistence-recovery.md).
