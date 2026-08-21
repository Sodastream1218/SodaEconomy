# Troubleshooting

Start with the first relevant section below. When asking for support, include the exact SodaEconomy,
Paper/Purpur and Java versions, storage backend, installed integrations and sanitized logs.

## SodaEconomy does not enable

1. Verify the server is Paper/Purpur in the supported range and the Java runtime is valid for that
   server.
2. Read the **first** SodaEconomy error in the startup log, not only the final plugin-disable line.
3. Check `config.yml` for invalid YAML or incomplete MYSQL settings.
4. Remove duplicate/old SodaEconomy JARs from `plugins/`.
5. Do not use a `-plain.jar` or Maven `original-*.jar`; install the official release JAR.

## MYSQL/MariaDB connection failed

Check:

- `storage.mysql.host` and `port`;
- database name exists;
- dedicated user/password are correct;
- user has the required privileges on the SodaEconomy database;
- firewall/container/network routing permits the connection;
- database service is running;
- TLS/public-key settings match the database policy.

Do not switch temporarily to local storage while several servers still use the SQL economy. That can
create divergent data.

## Migration was refused

This is usually a safety decision, not data loss. Read the complete startup log and
[Migration](migration.md). MYSQL→local migrations require the explicit single-server confirmation and
must not run while another network instance is active.

## `/eco reload` failed

The previous runtime settings remain active when a safe reload candidate is invalid. Check the console
for the validation error, correct the changed value and run `/eco reload` again.

Remember that storage, banking, persistence and Vault settings are startup-only; restarting is the
correct way to apply those changes.

## Vault is not detected

- Confirm Vault is installed and enabled.
- Confirm `integrations.vault.enabled: true`.
- Restart after installing/enabling Vault or changing Vault settings.
- Check that the consuming plugin requests a Vault economy provider rather than Vault's named-bank API.

## PlaceholderAPI placeholders do not work

- Confirm PlaceholderAPI is installed and enabled.
- Use the exact `%sodaeconomy_...%` identifiers from [PlaceholderAPI](placeholderapi.md).
- No separate eCloud expansion is required.
- No `/papi reload` is required after `/eco reload` for SodaEconomy's formatting/ranking settings.
- A `-` player value can mean the first authoritative snapshot is not yet available; persistent read
  failures do not intentionally become fake zero balances.

## `/bank` does not work

Banking is disabled by default. Enable `banking.enabled: true` and restart. The sender also needs
`sodaeconomy.bank.use`; `/bank setbalance` additionally needs `sodaeconomy.bank.admin`.

## A command says “no permission”

Compare the sender's nodes with [Permissions](permissions.md). `sodaeconomy.admin.update` and
`sodaeconomy.bank.admin` are deliberately separate from normal `/eco` administration.

## Update checker cannot reach GitHub

This does not disable SodaEconomy or any economy feature. Verify outbound HTTPS access/DNS if update
notifications are desired, or set `update-checker.enabled: false` and `/eco reload` to disable checks.
Enable `debug.integrations` only when a technical failure cause is needed.

## Economy data is temporarily unavailable

Read commands may report temporary unavailability instead of displaying invented zeros when the
backend cannot be read. Restore database/storage availability and review the logs. See
[Persistent read failure semantics](read-failure-semantics.md).

## Local recovery warning

Do not delete recovery files manually. They may represent accepted YAML/SQLite writes that still need
to be reconciled. See [Local persistence recovery](local-persistence-recovery.md).

## Before opening an issue

Collect:

- SodaEconomy version;
- Paper/Purpur version and build;
- Java version;
- storage backend and MySQL/MariaDB version when applicable;
- Vault/PlaceholderAPI/Floodgate versions when installed;
- exact reproduction steps;
- sanitized logs;
- relevant config with passwords/tokens removed.

Then use the repository's issue forms or Discussions as described in [Support](../.github/SUPPORT.md).
