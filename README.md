# SodaEconomy

**A reliable, auditable economy for Paper and Purpur — simple on one server, ready for networks.**

SodaEconomy provides wallets, optional personal bank accounts, Vault and PlaceholderAPI integration,
transaction history, rollback tooling, safe storage migration, and production-focused persistence.
It is designed to be easy to install on a single server while keeping the data-integrity guarantees
needed by larger multi-server setups.

> **v1.0.0 target:** Paper/Purpur 1.20.2+, Java 17+ bytecode, SQLite/YAML or MySQL/MariaDB storage.

**Start here:** [Installation](docs/installation.md) · [Configuration](docs/configuration.md) ·
[Commands](docs/commands.md) · [Permissions](docs/permissions.md) · [Documentation](docs/README.md) ·
[GitHub Releases](https://github.com/Sodastream1218/SodaEconomy/releases)

## Why SodaEconomy?

Many economy plugins are easy to start with but become harder to trust once multiple integrations,
storage migrations, retries, or network servers are involved. SodaEconomy is built around a small
set of predictable rules:

- **Money changes go through one transaction path.** Wallet mutations are validated, persisted and
  journaled instead of being scattered across commands and integrations.
- **Storage failures are not disguised as real zero balances.** Read failures remain failures, while
  legitimate empty accounts remain legitimate empty accounts.
- **Local storage is crash-aware.** YAML and SQLite use an ordered asynchronous persistence path with
  a compact recovery WAL for accepted writes that have not reached the backend yet.
- **Network storage is database-authoritative.** MySQL and MariaDB use transactional row locking,
  migration safeguards and multi-instance concurrency handling.
- **Optional integrations stay optional.** Vault, PlaceholderAPI and Floodgate are detected only when
  installed; SodaEconomy works without them.

The goal is reliability and clear administration, not exaggerated feature claims.

## Feature highlights

### Core economy

- Player wallets with configurable starting and maximum balances
- `/balance`, `/pay`, `/topbalance` and administrator economy commands
- Exact internal money representation in integer minor units
- Persistent UUID-based player identity registry
- English, German and Spanish message files

### Audit and administration

- Immutable wallet transaction journal
- Player history, transaction details, audit summaries and server statistics
- Reversing rollback transactions for eligible wallet operations
- Safe, atomic runtime reload for documented settings
- Privacy-friendly optional GitHub release checker with `/eco version`

### Banking

- Optional personal bank balances
- Wallet ↔ bank deposits and withdrawals
- Optional scheduled interest with a configurable per-account cap
- Administrative bank-balance control

### Storage and networks

- **SQLite** — recommended default for most single-server installations
- **YAML** — readable local storage for small/simple setups
- **MySQL / MariaDB** — shared network storage through MariaDB Connector/J
- Safe storage migration with explicit MySQL→local safeguards
- Tested schema migrations and shared-database concurrency paths

### Integrations

- **Vault** — optional economy provider for compatible shops, rewards and other plugins
- **PlaceholderAPI** — native cached `%sodaeconomy_...%` placeholders; no eCloud expansion required
- **Floodgate** — optional Bedrock identity classification while keeping UUIDs authoritative

## Support matrix

| Area | v1.0.0 support target |
| --- | --- |
| Server | Paper / Purpur 1.20.2+ within the modern Paper API line |
| Java | Java 17-compatible plugin bytecode; use the runtime required by your Paper version |
| SQLite | Supported; recommended single-server default |
| YAML | Supported; best suited to small/simple local setups |
| MySQL | Supported through the shared JDBC implementation |
| MariaDB | Supported through MariaDB Connector/J |
| Multi-server | Supported with one shared MySQL/MariaDB database |
| Vault | Optional |
| PlaceholderAPI | Optional |
| Floodgate | Optional |
| Folia | Not currently advertised as supported |
| Spigot/Bukkit-only | Not currently advertised as supported |

See the [full compatibility matrix](docs/compatibility.md) before deploying to a new server line.

## Quick start

1. Download the official `SodaEconomy-1.0.0.jar` from the GitHub Releases page or another official
   SodaEconomy distribution page.
2. Put the JAR into your Paper/Purpur server's `plugins/` directory.
3. Start the server once. SodaEconomy creates `plugins/SodaEconomy/config.yml` and its language files.
4. Keep the default `storage.type: SQLITE` unless you intentionally need YAML or a shared SQL database.
5. Adjust your currency, starting balance and optional features, then use `/eco reload` for settings
   documented as reload-safe or restart after startup-only changes.
6. Test `/balance`, `/pay` and `/topbalance` with a normal player account.

Optional integrations are automatic after installation: add Vault or PlaceholderAPI to the server and
restart when required. No separate SodaEconomy PlaceholderAPI expansion download is needed.

For the complete first-install flow, see [Installation](docs/installation.md).

## Choose your storage

| Backend | Recommended for | Notes |
| --- | --- | --- |
| SQLite | Most single servers | Default; local database file; strong balance between simplicity and reliability |
| YAML | Small/simple servers, human-readable local data | Supported, but not the preferred choice for large player counts |
| MySQL/MariaDB | Networks and multiple Paper instances | Shared authoritative database; use a dedicated `sodaeconomy` database account |

Changing storage is a migration operation. **Back up the current backend first** and read the
[database](docs/database.md) and [migration](docs/migration.md) guides before switching an existing server.

## Optional integrations

| Integration | Required? | What it adds |
| --- | --- | --- |
| Vault | No | Registers SodaEconomy as a Vault economy provider |
| PlaceholderAPI | No | Cached wallet/bank/total/rank placeholders for TAB, scoreboards, chat, GUIs and holograms |
| Floodgate | No | Classifies known Bedrock identities when the Floodgate API is available |
| Update checker | No | Asynchronously checks official GitHub Releases and informs administrators; never installs updates |

Read [Vault](docs/vault.md), [PlaceholderAPI](docs/placeholderapi.md), and the
[update checker guide](docs/update-checker.md) for exact behaviour.

## Commands

Common player commands:

```text
/balance
/pay <player> <amount>
/topbalance [limit]
/bank balance
/bank deposit <amount>
/bank withdraw <amount>
```

Administrator and audit tools live under `/eco`, for example:

```text
/eco balance <player>
/eco give <player> <amount>
/eco history <player> [page]
/eco transaction <transactionId>
/eco rollback <transactionId>
/eco stats [player]
/eco reload
/eco version [check]
```

See the [complete command reference](docs/commands.md) and
[permission reference](docs/permissions.md). The banking commands are available only when the banking
feature is enabled.

## Configuration

The default configuration is intentionally usable without an external database. Important sections:

- `storage` — SQLite/YAML/MySQL selection and migration safety
- `language`, `prefix`, `currency` — presentation and localization
- `balance`, `max_balance`, `leaderboard` — core economy behaviour
- `banking` — optional personal bank accounts and interest
- `integrations.vault` — Vault provider settings
- `update-checker` — optional GitHub release notifications
- `transactions` — history page size
- `persistence.async` — local write-behind/recovery behaviour
- `debug` — targeted diagnostic logging

The [configuration guide](docs/configuration.md) lists every meaningful option, its default and whether
`/eco reload` can apply it without a restart.

## PlaceholderAPI

When PlaceholderAPI is installed, SodaEconomy registers its expansion automatically. Examples:

```text
%sodaeconomy_balance%
%sodaeconomy_balance_formatted%
%sodaeconomy_bank_balance%
%sodaeconomy_total_balance_formatted%
%sodaeconomy_currency_symbol%
%sodaeconomy_baltop_position%
```

Placeholder callbacks use cached presentation snapshots and do not perform synchronous database I/O.
See [PlaceholderAPI](docs/placeholderapi.md) for the full list and failure semantics.

## Vault

Vault is optional. When installed and enabled, SodaEconomy registers one economy provider and routes
wallet deposits/withdrawals through the same transaction and journal path as native SodaEconomy
operations. Vault's named-bank API is **not** implemented; SodaEconomy's `/bank` feature is a separate
personal-bank model.

See [Vault integration](docs/vault.md).

## Developer API

Plugins should obtain `EconomyTransactionApi` and `PlayerIdentityApi` from Bukkit's
`ServicesManager`. Do not mutate `Storage`, `EconomyManager`, `TransactionService` or concrete storage
implementations directly.

New integrations should prefer the `BigDecimal` or `*Minor` money mutation APIs. The historical
`double` overloads remain for v1 compatibility.

See [Developer API](docs/developer-api.md) for examples, threading rules, idempotency and error semantics.

## Documentation

The documentation hub is [`docs/README.md`](docs/README.md). Useful starting points:

- [Installation](docs/installation.md)
- [Configuration](docs/configuration.md)
- [Commands](docs/commands.md)
- [Permissions](docs/permissions.md)
- [Database & networks](docs/database.md)
- [Updating & migration](docs/migration.md)
- [FAQ](docs/faq.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Compatibility](docs/compatibility.md)

## Updates, backups and support

Before upgrading SodaEconomy, changing storage backends or changing network topology, create a backup
of the active economy storage. Database/config migrations are designed to be safe, but a backup is
still the correct operational boundary for economy data.

For problems, start with [Troubleshooting](docs/troubleshooting.md), then use the repository's issue
forms or GitHub Discussions as described in [Support](.github/SUPPORT.md). Security vulnerabilities
must follow [SECURITY.md](.github/SECURITY.md) and should not be disclosed in public issues.

## Build contract

For contributors and maintainers: **Gradle is the canonical release build**. Maven remains a supported,
CI-verified parity build. The official public plugin JAR is the Gradle `shadowJar` output
`build/libs/SodaEconomy-1.0.0.jar`; the Gradle `-plain.jar` and Maven `original-*.jar` are not release
artifacts.

See [maintainer build contracts](docs/maintainers/build-contracts.md).

## License

SodaEconomy is **source available**, not OSI open source. It is distributed under Apache License 2.0
subject to the Commons Clause License Condition v1.0 and the express permissions in [`LICENSE`](LICENSE).
Use on monetized Minecraft servers and networks is expressly permitted; commercializing SodaEconomy
itself is restricted.

Read [`LICENSE`](LICENSE), [`LICENSE-FAQ.md`](LICENSE-FAQ.md), [`TRADEMARKS.md`](TRADEMARKS.md) and
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for the complete terms and attribution information.
