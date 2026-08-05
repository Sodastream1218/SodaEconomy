# Changelog

All notable changes to SodaEconomy will be documented in this file.

The project follows Semantic Versioning for public releases and uses prerelease identifiers such as
`alpha`, `beta`, and `rc` while compatibility is still being validated.

## [Unreleased]

### Added

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
