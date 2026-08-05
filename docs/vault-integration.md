# Optional Vault integration

SodaEconomy registers a `net.milkbowl.vault.economy.Economy` provider when the Vault plugin is
installed, enabled, and `integrations.vault.enabled` is `true`. Vault remains a soft dependency;
SodaEconomy starts normally when it is absent. The Vault API is build-time `provided`/`compileOnly`
and is never shaded into the plugin JAR.

## Consistency contract

Vault is synchronous, while SodaEconomy normally exposes durable asynchronous transactions. The
adapter is only a facade over `TransactionService`:

1. String player names are resolved through `PlayerIdentityApi`; unknown or ambiguous names fail.
2. Amounts are converted through `Money` to exact minor units.
3. The operation is submitted to the single ordered transaction executor.
4. YAML/SQLite drain older queued writes and commit this Vault mutation directly to their atomic
   backend before the runtime cache is updated. MySQL uses its existing ACID/row-lock path.
5. Vault receives `SUCCESS` only after the immutable journal record is durable.

A configured timeout may cancel an operation only while it is still waiting in the transaction
executor. Once durable execution has started, SodaEconomy waits for the definitive result instead
of returning a false failure that could later become a ghost transaction. Backend connection and
socket timeouts still bound authoritative MySQL failures. Slow definitive calls are rate-limited in
server logs.

## Configuration

```yaml
integrations:
  vault:
    enabled: true
    operation-timeout-millis: 3000
    warn-after-millis: 100
```

These settings are startup-only and are deliberately excluded from `/eco reload`. Currency and
prefix display settings remain runtime reloadable: `Vault#format` and all future Vault error
responses use the current SodaEconomy runtime snapshots.

## Accounts and identities

Modern `OfflinePlayer` overloads use UUIDs directly. Deprecated String overloads resolve only
through the persistent known-player registry. SodaEconomy never calls `Bukkit#getOfflinePlayer`
with an unknown name and never fabricates an account during a lookup. Java and Floodgate/Bedrock
players follow the same UUID path.

## Journal, audit, statistics and rollback

Vault deposits and withdrawals use the existing API transaction types and are marked with:

- origin identifier `Vault`
- `integration=vault`
- `operation=deposit` or `operation=withdraw`
- `vault_api=1.7.1`

They therefore appear in history, audit and statistics and can be rolled back like native
SodaEconomy transactions.

## Vault banks

`hasBankSupport()` returns `false`. Vault's named organisation-bank model is not equivalent to
SodaEconomy's personal savings accounts. All Vault bank methods return `NOT_IMPLEMENTED`; the
native `/bank` feature remains unchanged.

## Release smoke test

Before declaring Vault support stable, validate with the real Vault plugin and at least one typical
consumer (shop, rewards, crates or auction plugin): provider discovery, account creation, deposit,
withdrawal, insufficient funds, restart persistence, MySQL multi-instance access and `/eco reload`
currency formatting.
