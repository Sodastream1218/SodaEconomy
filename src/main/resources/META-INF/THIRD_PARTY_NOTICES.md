# Third-Party Notices

SodaEconomy uses, loads, compiles against, tests with, or is built by the
third-party components listed below. Each component remains subject to its own
license. This file is an inventory and attribution summary; it does not replace
an upstream license.

The distribution status is important:

- **Bundled** means code or native binaries are included in the SodaEconomy
  release JAR.
- **Runtime-loaded** means Paper resolves the component separately at server
  startup.
- **Provided/optional** means the server or another plugin supplies the API.
- **Test/build/CI only** means the component is not part of the released plugin
  JAR.

## Bundled in the SodaEconomy release JAR

### Xerial SQLite JDBC 3.46.1.3

- Coordinate: `org.xerial:sqlite-jdbc:3.46.1.3`
- Purpose: SQLite storage backend and bundled native SQLite libraries
- License: Apache License 2.0, with BSD-2-Clause and SQLite public-domain
  materials included by the upstream distribution
- Distribution: shaded into the release JAR without relocating `org.sqlite`,
  because the native JNI bindings depend on the original package names
- Local license copies:
  - `third-party-licenses/Apache-2.0.txt`
  - `third-party-licenses/BSD-2-Clause.txt`
  - `third-party-licenses/SQLite-Public-Domain.txt`
  - equivalent copies under `META-INF/licenses/` in the release JAR

No MySQL or MariaDB server JDBC driver is embedded in the SodaEconomy release
JAR.

## Loaded at runtime by Paper, not bundled

### MariaDB Connector/J 3.5.9

- Coordinate: `org.mariadb.jdbc:mariadb-java-client:3.5.9`
- Purpose: JDBC connectivity to both MariaDB and MySQL servers
- License: GNU Lesser General Public License v2.1 or later
  (`LGPL-2.1-or-later`)
- Distribution: declared in `plugin.yml` under `libraries`; Paper resolves it
  separately at runtime
- Build scope: test runtime in Maven and Gradle so the shared JDBC integration
  suite can run without embedding the driver

## Compile-only or server-provided integrations

### Paper API 1.20.2-R0.1-SNAPSHOT

- Coordinate: `io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT`
- Purpose: Minecraft server and plugin API
- License: Paper’s upstream repository describes the project as GPL-3.0 with
  identified contributor code available under MIT; applicable upstream notices
  remain authoritative
- Distribution: provided by Paper/Purpur and not bundled

### VaultAPI 1.7.1

- Coordinate: `com.github.MilkBowl:VaultAPI:1.7.1`
- Purpose: optional Vault economy-provider contract
- License: GNU Lesser General Public License v3.0 or later
  (`LGPL-3.0-or-later`)
- Distribution: compile-only/provided and not bundled; the installed Vault
  plugin supplies the API at runtime

### PlaceholderAPI 2.12.3

- Coordinate: `me.clip:placeholderapi:2.12.3`
- Purpose: optional placeholder expansion API used by SodaEconomy's internal
  `%sodaeconomy_...%` integration
- License: GNU General Public License v3.0 (`GPL-3.0`)
- Distribution: compile-only/provided and not bundled; the installed
  PlaceholderAPI plugin supplies the API at runtime

### Floodgate

- Project: GeyserMC Floodgate
- Purpose: optional Bedrock-player identification
- License: MIT License
- Distribution: no Floodgate artifact is declared or bundled; SodaEconomy uses
  reflective detection and `softdepend`

## Test-only libraries

### JUnit Jupiter and JUnit Platform 5.10.1

- Coordinates: JUnit Jupiter artifacts managed through
  `org.junit:junit-bom:5.10.1`, plus the JUnit Platform launcher
- Purpose: unit and integration testing
- License: Eclipse Public License 2.0 (`EPL-2.0`)
- Distribution: test only; not bundled

### MockBukkit for Minecraft 1.20, version 3.58.1

- Coordinate: `com.github.seeseemelk:MockBukkit-v1.20:3.58.1`
- Purpose: Paper/Bukkit plugin tests without a production server
- License: MIT License
- Distribution: test only; not bundled

The test suite also resolves Paper API, PlaceholderAPI, and MariaDB Connector/J under the
versions and licenses listed above.

## Build tooling included or declared by the repository

### Gradle

- Gradle Wrapper distribution: 8.10.2
- Shadow Gradle Plugin: `com.gradleup.shadow` 8.3.11
- Licenses: Gradle and Shadow are distributed under Apache License 2.0
- Purpose: compilation, testing, coverage, and release-JAR assembly
- Distribution: build tooling only; the Gradle wrapper JAR and scripts are
  stored in the repository, not in the plugin JAR

### Apache Maven

- Maven Wrapper distribution: 3.9.9
- Maven Compiler Plugin: 3.11.0
- Maven JAR Plugin: 3.3.0
- Maven Surefire Plugin: 3.2.5
- Maven Enforcer Plugin: 3.5.0
- Maven Shade Plugin: 3.5.3
- License: Apache License 2.0
- Purpose: alternate compilation, test, coverage, enforcement, and packaging
- Distribution: build tooling only; wrapper scripts are stored in the
  repository, not in the plugin JAR

### JaCoCo 0.8.12

- Coordinate: `org.jacoco:jacoco-maven-plugin:0.8.12`
- Purpose: code-coverage reporting and verification
- License: Eclipse Public License 2.0 (`EPL-2.0`)
- Distribution: build/test only; not bundled

## GitHub Actions used by CI

- `actions/checkout@v4` — MIT License
- `actions/setup-java@v4` — MIT License
- `gradle/actions/setup-gradle@v4` — upstream action license and notices apply
- Eclipse Temurin JDK 21 — used by CI only; not distributed with SodaEconomy
- MySQL 8.4 and MariaDB 11.8 container images — used as CI services only; not
  distributed with SodaEconomy

## Scope and maintenance

This inventory is derived from `pom.xml`, `build.gradle.kts`,
`gradle/wrapper/gradle-wrapper.properties`,
`.mvn/wrapper/maven-wrapper.properties`, `plugin.yml`, and
`.github/workflows/test.yml`.

When a dependency, wrapper, build plugin, CI action, or runtime library version
changes, this file and the embedded `META-INF/THIRD_PARTY_NOTICES.md` copy must
be reviewed in the same change.
