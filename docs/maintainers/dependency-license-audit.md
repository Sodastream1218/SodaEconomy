# Dependency License Audit

## Current JDBC status

The former embedded `com.mysql:mysql-connector-j` dependency remains removed.
SodaEconomy uses `org.mariadb.jdbc:mariadb-java-client:3.5.9`, licensed
LGPL-2.1-or-later, through Paper's external library loader. The MariaDB driver
is test-runtime-only in Maven and Gradle and is not included in the release
JAR.

Release verification rejects classes under:

- `com/mysql/`
- `de/sodaeconomy/libs/mysql/`
- `org/mariadb/jdbc/`
- `com/google/protobuf/`
- `net/milkbowl/vault/`

The separate PlaceholderAPI release check rejects:

- `me/clip/placeholderapi/`

SQLite JDBC 3.46.1.3 remains bundled. Its Apache-2.0, BSD-2-Clause, and SQLite
public-domain notices are documented in `THIRD_PARTY_NOTICES.md` and copied
under `third-party-licenses/` and `META-INF/licenses/`.

VaultAPI, Paper API, and PlaceholderAPI remain server-provided/compile-only in production.
VaultAPI is also present on the Gradle test classpath so Vault-specific tests can compile and run,
but it is not bundled into the release JAR. PlaceholderAPI 2.12.3 is GPL-3.0 and is not bundled into the release JAR. Floodgate remains an optional
reflective integration without a declared or bundled dependency.

## Final project license

SodaEconomy is distributed under the Apache License, Version 2.0, subject to
the Commons Clause License Condition v1.0 and the project-specific express
permission in `LICENSE`.

The express permission makes clear that monetized Minecraft server operation,
server ranks, shops, crates, donations, advertising, subscriptions, and
general hosting with SodaEconomy as one component remain permitted. The
commercial restriction applies to selling or commercially substituting the
SodaEconomy software itself.

See:

- `LICENSE`
- `LICENSE-FAQ.md`
- `TRADEMARKS.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/maintainers/history/licensing-release-audit.md`
