# Vault Integration

Vault is optional. SodaEconomy starts normally when Vault is absent. When Vault is installed, enabled
and `integrations.vault.enabled` is `true`, SodaEconomy registers one Vault `Economy` provider.

## Install

1. Install Vault on the Paper/Purpur server.
2. Keep `integrations.vault.enabled: true` (default).
3. Restart the server.
4. Verify the consuming shop/reward/plugin detects SodaEconomy through Vault.

Vault settings are startup-only; `/eco reload` does not re-register the provider.

## Transaction guarantees

Vault is a synchronous API while SodaEconomy's transaction system is durability-oriented. The adapter
routes deposits and withdrawals through SodaEconomy's transaction service rather than touching storage
directly. As a result, successful Vault wallet mutations participate in the normal journal, audit,
statistics, event and rollback model.

If a request is still safely cancellable before execution, the configured timeout may fail it. Once
a durable commit has started, SodaEconomy waits for the definitive result rather than reporting a
failure that could later become a committed transaction.

## Configuration

```yaml
integrations:
  vault:
    enabled: true
    operation-timeout-millis: 3000
    warn-after-millis: 100
```

See [Configuration](configuration.md) for option definitions.

## Player identities

Modern Vault `OfflinePlayer` operations use UUIDs. Deprecated String-name operations resolve through
SodaEconomy's persistent known-player registry. Unknown names do not create synthetic offline accounts.

## Vault bank API

Vault's named organisation-bank model is **not** the same as SodaEconomy's personal `/bank` feature.
Therefore SodaEconomy reports `hasBankSupport() == false` and Vault named-bank methods are not
implemented. Use SodaEconomy's native bank commands for personal bank balances.

## Compatibility checklist

Before relying on a new Vault consumer in production, test provider discovery, account creation,
deposit, withdrawal, insufficient funds, restart persistence and (for networks) shared SQL behaviour.
