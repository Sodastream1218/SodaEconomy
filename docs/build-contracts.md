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
