# Compatibility and Support Matrix

SodaEconomy ships as one Java 17-compatible plugin JAR for the supported modern Paper/Purpur line.
The table below is the public v1.0.0 support target; a new Minecraft/Paper line should not be advertised
until the release JAR has passed runtime smoke testing there.

## Public support matrix

| Component | v1.0.0 status | Notes |
| --- | --- | --- |
| Paper 1.20.2+ | Supported target | Compiled against Paper API `1.20.2-R0.1-SNAPSHOT` |
| Purpur 1.20.2+ | Supported target | Paper-compatible path; smoke-test the target build |
| Java 17+ | Supported bytecode target | Use the Java runtime required by the selected Paper version |
| SQLite | Supported | Recommended local/default backend |
| YAML | Supported | Local readable backend |
| MySQL 8.x | Supported | Shared JDBC path |
| MariaDB 11.x | Supported | Shared JDBC path; CI includes MariaDB 11.8 |
| Multi-server SQL | Supported | Multiple instances may share one MYSQL/MariaDB database |
| Vault | Optional / supported | Wallet provider; Vault named banks are not implemented |
| PlaceholderAPI | Optional / supported | Native cached expansion |
| Floodgate | Optional | Identity classification only |
| Update checker | Optional | GitHub Releases information only; can be disabled |
| Folia | Not currently advertised | Region-thread model needs a dedicated audit |
| Spigot/Bukkit-only | Not currently advertised | SodaEconomy targets Paper/Purpur APIs |

`plugin.yml` uses `api-version: 1.20`, while the project compile baseline is Paper API 1.20.2. The
`api-version` field cannot express `1.20.2`; the documented minimum therefore remains 1.20.2.

## Java compatibility

Maven and Gradle both compile with `--release 17`. This prevents a build running on a newer JDK from
accidentally linking newer Java APIs into the released plugin. Running on a newer Java runtime is
expected when required by the selected Paper release.

## Storage compatibility

The same release JAR supports YAML, SQLite and MYSQL/MariaDB. SQLite JDBC is bundled. MariaDB
Connector/J is declared through Paper's plugin library mechanism and is not shaded into the release
JAR.

Shared SQL integration tests cover both MySQL 8.4 and MariaDB 11.8 in CI. The active MYSQL schema is
versioned and includes the multi-instance runtime/maintenance coordination used by current releases.

## Optional integrations

Vault, PlaceholderAPI and Floodgate remain optional soft dependencies. Their absence must not prevent
SodaEconomy from enabling. VaultAPI and PlaceholderAPI classes are compile-time/server-provided and are
explicitly rejected from the release JAR by build verification.

## Release smoke-test expectations

Before claiming a newly supported Paper/Purpur line, verify at minimum:

1. clean startup and shutdown;
2. `/balance`, `/pay`, `/topbalance`, `/eco reload`;
3. YAML and SQLite restart persistence;
4. one MYSQL/MariaDB transaction against an isolated database;
5. Vault absent/present where applicable;
6. PlaceholderAPI absent/present and representative placeholders;
7. banking when it is part of the advertised test matrix;
8. no persistence warnings on clean shutdown.

For network releases, also run the existing two-instance MySQL/MariaDB concurrency tests.

## Deliberately unsupported assumptions

Do not infer support merely because the plugin happens to load on an environment not listed above.
In particular, Folia requires scheduler/threading review rather than a metadata-only declaration, and
substantially older Minecraft/Paper versions would require a separate compatibility strategy.
