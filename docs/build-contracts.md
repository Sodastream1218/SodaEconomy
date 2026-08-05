# Build Integration Contracts

This document records release-critical build contracts that must not drift while
SodaEconomy remains a single optional-integration plugin artifact.

## Vault

Vault support is an optional integration adapter. SodaEconomy must not require
Vault at runtime and must not shade VaultAPI or VaultAPI's legacy Bukkit
transitive dependency into the plugin JAR.

- Dependency: VaultAPI 1.7.1
- Maven scope: provided
- Gradle configuration: compileOnly
- Transitive dependencies: disabled
- Runtime metadata: `softdepend: [Vault, floodgate]`
- Forbidden metadata: `provides: [Vault]`

Vault remains a service provider integration only. SodaEconomy registers an
Economy implementation with Bukkit's `ServicesManager` when Vault is present;
it does not replace the Vault plugin itself.

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
