# PlaceholderAPI

SodaEconomy provides a native PlaceholderAPI expansion when PlaceholderAPI is installed. It is a
**soft dependency**: SodaEconomy starts normally without it, and no separate eCloud expansion is
required.

All placeholders use the `sodaeconomy` identifier.

## Placeholders

| Placeholder | Description | Example | Player context |
| --- | --- | --- | --- |
| `%sodaeconomy_balance%` | Wallet balance with two decimal places | `12500.50` | Yes |
| `%sodaeconomy_balance_formatted%` | Wallet using current SodaEconomy currency formatting | `$12500.50` | Yes |
| `%sodaeconomy_balance_short%` | Compact wallet amount (`K`, `M`, `B`, `T`) | `12.5K` | Yes |
| `%sodaeconomy_bank_balance%` | Personal bank balance | `50000.00` | Yes |
| `%sodaeconomy_bank_balance_formatted%` | Formatted personal bank balance | `$50000.00` | Yes |
| `%sodaeconomy_total_balance%` | Wallet + personal bank when banking is enabled | `62500.50` | Yes |
| `%sodaeconomy_total_balance_formatted%` | Formatted wallet + bank total | `$62500.50` | Yes |
| `%sodaeconomy_currency_symbol%` | Current configured currency symbol/text | `$` | No |
| `%sodaeconomy_baltop_position%` | One-based leaderboard position, or `-` | `3` | Yes |
| `%sodaeconomy_rank%` | Alias of `baltop_position` | `3` | Yes |

When banking is disabled, bank values resolve as zero. Ranking returns `-` when the leaderboard is
disabled, the player is outside the configured maximum, or the required data is not yet available.

Unknown identifiers return `null` to PlaceholderAPI. A missing player context has no meaningful
player account; monetary player placeholders resolve as zero and ranking resolves as `-`.

## Failure safety

Placeholder callbacks never perform SQL, disk I/O, `Future#get`, `join()` or other synchronous
waiting. They read an in-memory presentation cache that is refreshed asynchronously.

Wallet and bank snapshots are tracked independently. If a backend refresh fails after a successful
snapshot, the last valid values remain available instead of being replaced by fake zeros. Before the
first successful required snapshot, player-data placeholders return `-` to distinguish temporary
unavailability from a real zero balance.

## Performance

The cache refreshes asynchronously (currently every 20 seconds while the integration is active), and
confirmed local transactions update relevant cached values immediately. Multi-server changes are
reconciled by the periodic authoritative snapshot.

All listed placeholder resolutions are O(1) against cached/immutable presentation state.

## Formatting and precision

Raw amounts are generated from SodaEconomy's exact integer minor units and use locale-independent
decimal formatting. Formatted amounts share the same live currency settings as commands.

Compact examples:

```text
999      -> 999
1000     -> 1K
1250     -> 1.25K
1000000  -> 1M
1250000  -> 1.25M
```

## `/eco reload`

No `/papi reload` is required after a SodaEconomy runtime reload. The expansion remains registered.
After a successful `/eco reload`:

- `currency.symbol` updates `currency_symbol` and every formatted money placeholder immediately;
- `currency.display_after_amount` updates formatted placement immediately;
- `leaderboard.enabled` and `leaderboard.max-entries` affect rank placeholders immediately.

Raw cached balances are unaffected by presentation-only configuration changes.

## Deliberately unsupported placeholders

SodaEconomy v1.0.0 does not expose a separate `%sodaeconomy_currency%` because the configuration has a
currency **symbol/text**, not a second currency-name model. It also does not run transaction-history
queries from placeholder callbacks, so persistent audit counters are intentionally not exposed as
high-frequency placeholders.

For configuration reload details see [Configuration](configuration.md).
