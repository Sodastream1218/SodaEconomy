# Third-Party Notices

SodaEconomy uses or integrates with the following third-party components. This file is a notice
summary and does not replace the corresponding license texts distributed by those projects.

## Bundled in the release JAR

### Xerial SQLite JDBC 3.46.1.3

- Project: `org.xerial:sqlite-jdbc`
- Purpose: SQLite storage backend
- License: Apache License 2.0; the distribution also contains BSD-licensed SQLite components
- Distribution: shaded without relocating `org.sqlite`, because its native bindings require the
  original package names

## Loaded by Paper, not bundled in the release JAR

### MariaDB Connector/J 3.5.9

- Project: `org.mariadb.jdbc:mariadb-java-client`
- Purpose: JDBC connectivity to both MySQL and MariaDB servers
- License: LGPL-2.1-or-later
- Distribution: resolved by Paper from the `plugin.yml` `libraries` declaration

## Compile-only / server-provided APIs

### Paper API 1.20.2

- Purpose: Minecraft server API
- Distribution: provided by the Paper/Purpur server; not bundled

### VaultAPI 1.7.1

- Purpose: optional Vault economy-provider contract
- License: LGPL-3.0-or-later
- Distribution: provided by the Vault plugin; not bundled

Floodgate support is implemented reflectively and does not add a compiled or bundled Floodgate
dependency.
