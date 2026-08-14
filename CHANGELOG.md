# Changelog

All notable changes to SodaEconomy will be documented in this file.

The project follows Semantic Versioning for public releases and uses prerelease identifiers such as
`alpha`, `beta`, and `rc` while compatibility is still being validated.

## [Unreleased]

### Changed

- Declared Gradle as the canonical release build while keeping Maven as a CI-verified parity build.
- Added exact public money mutation APIs using canonical minor units and `BigDecimal` without removing the existing `double` compatibility methods.
- Replaced the example MYSQL `root` account with a dedicated `sodaeconomy` account and documented least-privilege database permissions.
- Standardized public plugin metadata and storage startup diagnostics on English.
- Corrected pre-release legacy API deprecation metadata from `since = "1.1"` to `since = "1.0"`.
- Hardened persistent read semantics so storage/backend failures complete read futures exceptionally instead of masquerading as legitimate zero balances, empty snapshots, empty histories or zeroed statistics.
- PlaceholderAPI now retains the last successful wallet/bank snapshot independently during backend failures and exposes `-` before the first successful player-data snapshot instead of inventing zero economy data.
- Read-only commands now report localized temporary economy-data unavailability rather than displaying false zero/empty results.
- Redesigned YAML/SQLite crash recovery as a bounded, checksummed pending-mutation WAL instead of rewriting the complete lifetime wallet journal for every accepted local mutation.
- Added idempotent startup reconciliation, legacy recovery-file migration, atomic compaction safeguards and direct-write protection against stale recovery residue.
- Added recovery corruption, precision, crash-boundary, compaction and opt-in 10k/50k/100k filesystem stress tests.

### Fixed

- Hardened MariaDB 11.8 cross-instance wallet concurrency by using `READ COMMITTED` on JDBC connections and expanding transient InnoDB retry handling with capped exponential backoff plus jitter.

### Fixed

- Fixed MariaDB 11.8 integration-test startup by replacing the mutation-gate `FOR SHARE` read with `LOCK IN SHARE MODE`, preserving shared-lock semantics while avoiding a MySQL-only syntax edge.

### Fixed

- Hardened the MySQL/MariaDB legacy exact-balance migration query by removing a dialect-sensitive parameterized `LIMIT ... FOR UPDATE` pattern during schema initialization.
- Added real `BankManager` facade tests for asynchronous bank operations and interval-aware interest instead of lowering the JaCoCo coverage threshold.

### Fixed

- Fixed the Gradle VaultAPI compile classpath by using the documented `com.github.MilkBowl:VaultAPI:1.7` API line for both production `compileOnly` and test `testImplementation`, while continuing to reject Vault API classes in the release JAR.

### Fixed

- Fixed the Gradle test classpath for Vault-facing MockBukkit tests by adding VaultAPI to the test configuration without bundling it into the release JAR.
- Extended release-JAR verification to reject accidentally shaded Vault API classes.

### Added

- Added an optional native PlaceholderAPI expansion for high-frequency wallet, bank, total, currency-symbol and leaderboard placeholders.
- Added exact minor-unit read snapshots to the supported asynchronous economy API for presentation integrations.
- Added shared exact currency formatting and deterministic leaderboard ranking utilities used by commands and PlaceholderAPI.
- Added PlaceholderAPI integration documentation, reload guarantees, performance classification and regression tests.
- Added the final SodaEconomy source-available license package based on Apache
  License 2.0 with Commons Clause License Condition v1.0.
- Added a license FAQ, trademark and naming policy, top-level NOTICE, expanded
  third-party dependency inventory, embedded JAR notices, and bundled
  third-party license copies.
- Added explicit contribution-licensing and contributor-rights terms without
  introducing a separate CLA.

### Changed

- Replaced the embedded MySQL Connector/J with Paper-provided MariaDB
  Connector/J 3.5.9 while preserving the existing MYSQL configuration, schema,
  and transaction behavior.
- Added identical CI integration coverage for MySQL 8.4 and MariaDB 11.8 LTS.
- Expanded public repository infrastructure and maintainer documentation.

<!--
Release template:

## [1.0.0-alpha.1] - YYYY-MM-DD

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

[Unreleased]: https://github.com/Sodastream1218/SodaEconomy/compare/v1.0.0-alpha.1...HEAD
[1.0.0-alpha.1]: https://github.com/Sodastream1218/SodaEconomy/releases/tag/v1.0.0-alpha.1
-->
