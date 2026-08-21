# FAQ

## Does SodaEconomy require Vault?

No. Vault is optional. Install it only when another plugin needs a Vault economy provider.

## Does SodaEconomy require PlaceholderAPI?

No. PlaceholderAPI is optional. If installed, SodaEconomy registers its `%sodaeconomy_...%` expansion
automatically.

## Which storage should I choose?

Use SQLite for most single servers. Use MYSQL/MariaDB for multiple servers that intentionally share
one economy. YAML is supported for small/simple setups where readable local files are preferred.

## Can I use MariaDB even though the config says `storage.type: MYSQL`?

Yes. `MYSQL` is the compatibility name of SodaEconomy's shared JDBC storage mode. It supports MySQL
and MariaDB through MariaDB Connector/J.

## Can multiple Paper servers share the same economy?

Yes, when all instances are configured to use the same MYSQL/MariaDB database. Read
[Database & networks](database.md) before deploying a network.

## Can I switch from SQLite/YAML to MariaDB later?

Yes. Storage migration is supported, but it is a startup operation. Back up first and follow the
[Migration](migration.md) guide.

## Can I switch from MySQL/MariaDB back to SQLite or YAML?

Yes, but this direction has an explicit single-server safety confirmation. Stop every other instance
using the shared database before enabling `storage.migration.confirm-single-server-mysql`.

## Can I reload the config without restarting?

Partially. `/eco reload` safely reloads language, prefix, leaderboard, currency and update-checker
settings. Storage, banking, persistence and Vault settings require restart.

## Is banking required?

No. Banking is disabled by default and the core wallet economy works without it.

## Does SodaEconomy support Vault banks?

No. Vault's named-bank model is different from SodaEconomy's personal `/bank` accounts. SodaEconomy
registers a Vault wallet economy provider but reports Vault bank support as unavailable.

## Does PlaceholderAPI query the database every time a placeholder is shown?

No. The expansion reads cached presentation snapshots. It does not perform synchronous SQL/disk I/O
inside placeholder callbacks.

## Does the update checker download anything?

No. It only performs an asynchronous HTTPS GET to the official GitHub Releases API and informs
administrators. It never downloads or replaces a plugin JAR.

## Does SodaEconomy send telemetry?

No. The update checker does not transmit player names/UUIDs, balances, transactions, server name,
plugin list, database information or a generated telemetry identifier. Normal network metadata such as
the source IP is visible to the remote HTTPS service as with any outbound request.

## Where should I report a bug?

Use the GitHub bug-report form after checking [Troubleshooting](troubleshooting.md). Security
vulnerabilities must follow `.github/SECURITY.md` instead of a public issue.
