# Stage 30 CI Fix: VaultAPI Gradle Coordinate

## Problem

The Stage 29 GitHub Actions Gradle run progressed past the earlier test-only
VaultAPI failure, but then failed during `:compileJava`. The production Vault
adapter imports `net.milkbowl.vault.economy.Economy` and `EconomyResponse`, so
those API classes must be available on the Gradle production compile classpath.

The log showed `package net.milkbowl.vault.economy does not exist` in
`VaultIntegrationManager` and `VaultEconomyProvider`.

## Root cause

Stage 29 correctly added VaultAPI to the Gradle test classpath, but the project
still used the three-part `1.7.1` coordinate in Gradle. VaultAPI's own
documentation describes API dependency versions as two-part API lines and shows
`com.github.MilkBowl:VaultAPI:1.7` for Gradle `compileOnly`.

## Fix

Stage 30 changes only the VaultAPI dependency coordinate/version:

- Maven property: `1.7`
- Gradle production configuration: `compileOnly("com.github.MilkBowl:VaultAPI:1.7")`
- Gradle test configuration: `testImplementation("com.github.MilkBowl:VaultAPI:1.7")`
- Transitives remain disabled/excluded where already configured
- Release-JAR verification still rejects `net/milkbowl/vault/`

## Compatibility

This is a build metadata fix only. It does not change:

- production Java source behavior
- database schemas or migrations
- PlaceholderAPI integration code
- Vault runtime soft-dependency behavior
- SQLite or MariaDB behavior
- plugin commands, permissions, or configuration

Vault remains optional at runtime and server-provided.
