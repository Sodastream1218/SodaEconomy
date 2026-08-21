# Installing SodaEconomy

This guide covers a normal server installation. For configuration details, database migration and
network setups, continue with the linked documentation after the first successful startup.

## Requirements

- Paper or Purpur in the supported modern server range (v1.0.0 target: 1.20.2+)
- A Java runtime supported by that server; SodaEconomy itself is compiled for Java 17
- Write access to the server's `plugins/` directory

Vault, PlaceholderAPI and Floodgate are optional.

## Install

1. Download the official `SodaEconomy-1.0.0.jar` release artifact.
2. Stop the server.
3. Copy the JAR into `plugins/`.
4. Start the server.
5. Confirm that SodaEconomy enables successfully and creates `plugins/SodaEconomy/config.yml`.
6. Join with a player and run `/balance`.

The default storage backend is SQLite and needs no external database setup.

## Optional integrations

- **Vault:** install Vault and restart. SodaEconomy registers as an economy provider when
  `integrations.vault.enabled: true`.
- **PlaceholderAPI:** install PlaceholderAPI and restart. The `%sodaeconomy_...%` expansion registers
  automatically; there is no separate eCloud expansion to download.
- **Floodgate:** optional; when available, SodaEconomy can classify known Bedrock identities.

## MariaDB / MySQL

Do not switch an existing server to `storage.type: MYSQL` before reading
[`docs/database.md`](docs/database.md). Create a dedicated database/user, configure the credentials,
back up the current storage, then restart. Storage changes are startup-only and may trigger migration.

## Next steps

- [Detailed installation](docs/installation.md)
- [Configuration](docs/configuration.md)
- [Commands](docs/commands.md)
- [Permissions](docs/permissions.md)
- [FAQ](docs/faq.md)
- [Troubleshooting](docs/troubleshooting.md)
