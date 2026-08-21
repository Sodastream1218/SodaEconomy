# Changelog

All notable SodaEconomy release changes are documented here. Public versions follow Semantic
Versioning; prereleases may use `alpha`, `beta` and `rc` identifiers.

## [Unreleased]

No release-visible changes recorded after the v1.0.0 release-candidate freeze.

## 1.0.0 — prepared for first stable release

### Added

- Core wallet economy with player, administrator, batch-payment and leaderboard commands.
- Optional personal banking with deposits, withdrawals, administrator balances and scheduled interest.
- Immutable wallet transaction journal with history, transaction inspection, audit, statistics and
  reversing rollback transactions.
- YAML, SQLite and shared MySQL/MariaDB storage with tested migrations and multi-instance safeguards.
- Ordered local asynchronous persistence with bounded retries, backpressure and a compact crash-recovery WAL.
- Persistent UUID/name identity registry with optional Floodgate/Bedrock classification.
- Optional Vault economy provider routed through the normal transaction/journal path.
- Native optional PlaceholderAPI expansion with cached wallet, bank, total, currency and rank values.
- Public asynchronous `EconomyTransactionApi` and read-only `PlayerIdentityApi` services.
- Exact `BigDecimal` and minor-unit money mutation methods while retaining `double` compatibility overloads.
- Safe atomic `/eco reload` for language, prefix, leaderboard, currency and update-checker settings.
- Optional asynchronous GitHub Releases update checker, `/eco version`, console/admin notifications,
  release channels and privacy-bounded failure handling.
- English, German and Spanish user message files.
- Gradle/Maven parity verification, MySQL 8.4 and MariaDB 11.8 integration CI, coverage gates and release-JAR checks.

### Changed

- Gradle is the canonical release build; Maven remains a CI-verified parity build.
- MariaDB Connector/J is the server-side JDBC driver for the shared MySQL/MariaDB storage mode.
- Public storage/read paths distinguish backend failure from legitimate zero/empty economy data.
- Default SQL examples use a dedicated `sodaeconomy` account instead of a database superuser.
- Public technical metadata, config comments and repository documentation are standardized on English.

### Compatibility notes

- Supported target: Paper/Purpur 1.20.2+ within the documented modern API line.
- Java bytecode target: 17.
- Vault, PlaceholderAPI and Floodgate are optional.
- Folia and Spigot/Bukkit-only servers are not advertised as supported in v1.0.0.

### Known boundaries

- Vault named banks are not implemented; SodaEconomy's native `/bank` feature is a personal-bank model.
- Pure bank administration/interest is not represented by the wallet journal in the same way as wallet mutations.
- The update checker does not download or install updates.

