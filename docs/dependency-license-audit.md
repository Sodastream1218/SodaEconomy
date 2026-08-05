# Dependency License Audit

## Current JDBC status

The former embedded `com.mysql:mysql-connector-j` dependency has been removed. SodaEconomy now
uses `org.mariadb.jdbc:mariadb-java-client:3.5.9`, licensed LGPL-2.1-or-later, through Paper's
external library loader. The MariaDB driver is test-only in Maven and test-runtime-only in Gradle and is not included
in the release JAR.

Release verification rejects classes under:

- `com/mysql/`
- `de/sodaeconomy/libs/mysql/`
- `org/mariadb/jdbc/`
- `com/google/protobuf/`

SQLite JDBC remains bundled and must retain its Apache-2.0/BSD notices. VaultAPI and Paper API remain
server-provided. See `THIRD_PARTY_NOTICES.md` for the release notice inventory.

## License decision impact

The previous MySQL Connector/J GPL/FOSS-exception blocker has been removed from the distributed
artifact. This clears the JDBC dependency obstacle identified for SodaEconomy's planned
source-available licensing model. The final project license still requires a separate explicit
maintainer decision and legal review where appropriate.
