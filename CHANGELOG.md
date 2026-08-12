# Changelog

All notable changes to SodaEconomy will be documented in this file.

The project follows Semantic Versioning for public releases and uses prerelease identifiers such as
`alpha`, `beta`, and `rc` while compatibility is still being validated.

## [Unreleased]

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
