# Installation

SodaEconomy is designed to start with useful defaults. A normal single-server installation does not
need Vault, PlaceholderAPI or an external database.

## Requirements

| Requirement | v1.0.0 target |
| --- | --- |
| Server | Paper or Purpur 1.20.2+ within the supported modern API line |
| Java | SodaEconomy bytecode targets Java 17; run the Java version required by your Paper build |
| Storage | SQLite (default), YAML, MySQL or MariaDB |

Not currently advertised as supported: Folia, Spigot/Bukkit-only servers, Paper/Purpur below 1.20.2,
or Java runtimes below 17.

## 1. Download the correct JAR

Use the official release artifact:

```text
SodaEconomy-1.0.0.jar
```

For project maintainers this is the canonical Gradle `shadowJar`. Do not install a `-plain.jar`, a
Maven `original-*.jar`, or a test artifact on a production server.

## 2. Install and start

1. Stop the server.
2. Put `SodaEconomy-1.0.0.jar` in `plugins/`.
3. Start the server.
4. Confirm that the console reports SodaEconomy as enabled.
5. Check that `plugins/SodaEconomy/config.yml` and the language files were created.

The default backend is SQLite, so no external service is required for the first startup.

## 3. Verify the basics

Join with a player and run:

```text
/balance
/topbalance
```

With two players online, also verify:

```text
/pay <player> 10
```

## 4. Configure your server

Common first changes:

```yaml
language: "EN"
balance: 1000
max_balance: 100000

currency:
  symbol: "$"
  display_after_amount: false
```

Language, prefix, leaderboard, currency display and update-checker settings are reload-safe with:

```text
/eco reload
```

Storage, banking, persistence and Vault settings are startup-only and require a full restart. See
[Configuration](configuration.md) for the complete matrix.

## 5. Optional banking

Banking is disabled by default. To enable personal bank accounts:

```yaml
banking:
  enabled: true
```

Restart the server. Players can then use `/bank balance`, `/bank deposit <amount>` and
`/bank withdraw <amount>`.

## 6. Optional Vault

Install Vault when another plugin expects a Vault economy provider. SodaEconomy detects Vault on
startup and registers its provider when `integrations.vault.enabled` is `true`.

See [Vault](vault.md).

## 7. Optional PlaceholderAPI

Install PlaceholderAPI when scoreboards, TAB lists, chat formatters, GUIs or hologram plugins need
SodaEconomy values. SodaEconomy registers its own expansion automatically; no separate eCloud
expansion is required.

See [PlaceholderAPI](placeholderapi.md).

## 8. Optional MariaDB/MySQL

Use a shared SQL database when multiple Paper instances need one economy or when an external database
is operationally preferred. Create the database and dedicated account first, configure
`storage.mysql`, then change:

```yaml
storage:
  type: MYSQL
```

Restart to apply the storage change. If this is an existing server, **back up the current backend
first** and read [Database & networks](database.md) and [Updating & migration](migration.md).

## 9. Update checker

The informational update checker is enabled by default. It performs an asynchronous HTTPS GET to the
official GitHub Releases API and can notify console/admins of a newer release. It never downloads or
installs files and sends no SodaEconomy telemetry.

Disable it with:

```yaml
update-checker:
  enabled: false
```

This setting is reload-safe. See [Update checker](update-checker.md).

## After installation

- [Commands](commands.md)
- [Permissions](permissions.md)
- [Configuration](configuration.md)
- [FAQ](faq.md)
- [Troubleshooting](troubleshooting.md)
