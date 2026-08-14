# Build Integration Contracts

This document records release-critical build contracts that must not drift while
SodaEconomy remains a single optional-integration plugin artifact.

## Canonical build and Maven parity

**Gradle is the canonical SodaEconomy release build.** The official release artifact is produced by
`shadowJar`, and the MySQL 8.4 / MariaDB 11.8 integration matrix, JaCoCo gate, and release-JAR
content checks are authoritative in the Gradle CI path.

Maven remains a supported secondary build for contributors and IDE/tooling compatibility. CI runs
`./mvnw -B -ntp clean verify` as a separate parity job and inspects the Maven shaded JAR for the
same forbidden server-provided APIs/drivers. Maven is not a second source of truth for published
release artifacts.

Any dependency, Java target, test-scope, shading, or optional-integration change must keep both
build definitions green. A change that passes Gradle but fails Maven (or vice versa) is a failed
build-contract change, not an acceptable release state.

## Vault

Vault support is an optional integration adapter. SodaEconomy must not require
Vault at runtime and must not shade VaultAPI or VaultAPI's legacy Bukkit
transitive dependency into the plugin JAR.

- Dependency: VaultAPI 1.7
- Coordinate: `com.github.MilkBowl:VaultAPI:1.7`
- Version rationale: VaultAPI documents two-part API versions; the API line is `1.7`, not a three-part plugin release version
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

## Local persistence recovery

YAML/SQLite async persistence uses the internal version-2 `local-persistence-recovery.wal` only as
a bounded durability bridge for unresolved queue writes. The release contract is:

- no complete historical journal scan/write per accepted mutation;
- canonical `long` minor units in recovery records;
- one forced compact pending record before local acceptance;
- stale records after backend commit must reconcile idempotently;
- queue drain removes recovery payload;
- legacy `local-persistence-recovery.yml` version 1 remains upgrade-readable;
- corrupt recovery state fails closed and is preserved;
- an internal 256 MiB WAL ceiling prevents unbounded local disk growth beyond ordinary queue backpressure;
- heavy filesystem load tests use the `recovery-stress` JUnit tag and are excluded from normal tests.

Release-candidate stress command:

```bash
./gradlew localPersistenceStressTest -Dsodaeconomy.recovery.stress.mutations=100000
```
