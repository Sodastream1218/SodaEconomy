# Build Integration Contracts

This document records release-critical build contracts that must not drift while
SodaEconomy remains a single optional-integration plugin artifact.

## Vault

Vault support is an optional integration adapter. SodaEconomy must not require
Vault at runtime and must not shade VaultAPI or VaultAPI's legacy Bukkit
transitive dependency into the plugin JAR.

- Dependency: VaultAPI 1.7.1
- Maven scope: provided
- Gradle production configuration: compileOnly
- Gradle test configuration: testImplementation, because Gradle tests do not inherit compileOnly
- Transitive dependencies: disabled for both configurations
- Runtime metadata: `softdepend: [Vault, floodgate, PlaceholderAPI]`
- Forbidden metadata: `provides: [Vault]`
- Forbidden release-JAR package: `net/milkbowl/vault/`

Vault remains a service provider integration only. SodaEconomy registers an
Economy implementation with Bukkit's `ServicesManager` when Vault is present;
it does not replace the Vault plugin itself.

## PlaceholderAPI

PlaceholderAPI support is an optional internal expansion. SodaEconomy must load and run without
PlaceholderAPI and must never shade PlaceholderAPI classes into the release JAR.

- Dependency: PlaceholderAPI 2.12.3
- Maven scope: provided
- Gradle configuration: compileOnly
- Repository: `https://repo.helpch.at/releases/`
- Runtime metadata: `softdepend: [Vault, floodgate, PlaceholderAPI]`
- Expansion identifier: `sodaeconomy`
- Forbidden release-JAR package: `me/clip/placeholderapi/`

The PlaceholderAPI-specific `PlaceholderExpansion` subclass is isolated behind a bootstrap class
that contains no PlaceholderAPI type in its own signature. The expansion is loaded only after the
server confirms PlaceholderAPI is enabled.

## JDBC driver

The published plugin JAR must not embed a MySQL or MariaDB JDBC driver.

- Runtime library: `org.mariadb.jdbc:mariadb-java-client:3.5.9`
- Runtime delivery: Paper `plugin.yml` `libraries`
- Maven: test scope only
- Gradle: `testRuntimeOnly` only
- JDBC scheme: `jdbc:mariadb:`
- Storage/config name: remains `MYSQL`
- Forbidden release-JAR packages: `com/mysql/`, `de/sodaeconomy/libs/mysql/`,
  `org/mariadb/jdbc/`, `com/google/protobuf/`
