# Release Process

This is the maintainer checklist for an official SodaEconomy release.

## 1. Freeze release inputs

- confirm `pom.xml`, `build.gradle.kts` and `plugin.yml` use the intended version;
- update `CHANGELOG.md` and the prepared release notes;
- verify the support matrix and known limitations;
- ensure no development-only secrets or runtime databases are tracked.

## 2. Verification

Gradle is the canonical release build:

```bash
./gradlew clean test
./gradlew mysqlIntegrationTest jacocoTestReport jacocoTestCoverageVerification shadowJar
```

Maven remains a required parity check:

```bash
./mvnw -B -ntp clean verify
```

GitHub Actions must be green, including MySQL 8.4, MariaDB 11.8 and Maven build parity.

## 3. Runtime smoke tests

Run the matrix in `docs/compatibility.md` on the server versions being advertised. Validate optional
integrations both absent and present where possible.

## 4. Release artifact

The canonical public binary is the Gradle Shadow JAR:

```text
build/libs/SodaEconomy-<version>.jar
```

Do **not** publish:

```text
build/libs/SodaEconomy-<version>-plain.jar
target/original-SodaEconomy-<version>.jar
```

The Maven shaded output is a parity artifact, not the canonical public release artifact.

Generate and publish a SHA-256 checksum alongside the official JAR when practical.

## 5. GitHub Release

- create tag `v<version>`;
- use `SodaEconomy v<version>` as the release title;
- paste/adapt `docs/release-notes/<version>.md`;
- upload only the canonical public JAR plus checksum/signature artifacts that are intentionally public;
- mark alpha/beta/RC releases as prereleases; stable releases must not be marked prerelease;
- publish, then verify the public release page and update-checker visibility.

## 6. Post-release

- verify the download installs on a clean Paper server;
- verify `/eco version` sees the expected official release/channel;
- update the `[Unreleased]` section for future work;
- monitor issues/security reports for release regressions.

## Repository presentation settings

Before the first public release, verify the GitHub repository **About** panel communicates the product
without requiring visitors to read technical internals first.

Suggested description:

```text
Reliable, auditable economy plugin for Paper/Purpur with SQLite/MariaDB, Vault, PlaceholderAPI, banking, rollback and network support.
```

Suggested topics:

```text
minecraft  paper  purpur  minecraft-plugin  economy  vault  placeholderapi  mariadb  sqlite
```

Point the website field to the primary public project/documentation page (for example Modrinth once
published). Keep Releases, Issues/Discussions and the Security tab enabled when they are part of the
support workflow.
