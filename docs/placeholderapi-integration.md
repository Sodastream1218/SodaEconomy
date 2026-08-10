# PlaceholderAPI integration

SodaEconomy provides a native, internal PlaceholderAPI expansion when PlaceholderAPI is installed.
PlaceholderAPI is a **soft dependency**: no SodaEconomy feature requires it, and the plugin starts
normally when PlaceholderAPI is absent.

The expansion identifier is `sodaeconomy`, so all placeholders use the
`%sodaeconomy_...%` namespace. No separate eCloud expansion is required.

## Supported placeholders

| Placeholder | Meaning | Example | Player context |
| --- | --- | --- | --- |
| `%sodaeconomy_balance%` | Exact cached wallet balance, fixed to two decimals | `12500.50` | Required for non-zero player value |
| `%sodaeconomy_balance_formatted%` | Wallet balance using SodaEconomy's live currency display settings | `$12500.50` | Required for non-zero player value |
| `%sodaeconomy_balance_short%` | Compact wallet balance using `K`, `M`, `B`, `T` suffixes | `12.5K` | Required for non-zero player value |
| `%sodaeconomy_bank_balance%` | Cached personal bank balance; `0.00` when banking/account is unavailable | `50000.00` | Required for non-zero player value |
| `%sodaeconomy_bank_balance_formatted%` | Bank balance using SodaEconomy's live currency display settings | `$50000.00` | Required for non-zero player value |
| `%sodaeconomy_total_balance%` | Wallet plus the personal bank balance when banking is enabled | `62500.50` | Required for non-zero player value |
| `%sodaeconomy_total_balance_formatted%` | Total balance using SodaEconomy's live currency display settings | `$62500.50` | Required for non-zero player value |
| `%sodaeconomy_currency_symbol%` | Currently active configured currency symbol | `$` | No |
| `%sodaeconomy_baltop_position%` | One-based position within the configured leaderboard maximum; `-` when unavailable | `3` | Yes |
| `%sodaeconomy_rank%` | Intentional alias of `baltop_position` | `3` | Yes |

Unknown placeholder identifiers return `null` to PlaceholderAPI so they remain invalid placeholders.
A null/missing player context resolves monetary player values as zero and ranking values as `-`.
Offline players are supported when PlaceholderAPI supplies their UUID and that account exists in the current cached
economy snapshot; the callback never performs an offline-player lookup or other Bukkit/Paper I/O.

## Deliberately unsupported placeholders

`%sodaeconomy_currency%` is not exposed in 1.0.0 because SodaEconomy currently has no configured
currency *name*. The authoritative runtime configuration contains only `currency.symbol` and
`currency.display_after_amount`; inventing a separate name would create a second currency model.

`%sodaeconomy_transactions_total%` is not exposed because transaction totals are persistent audit
statistics and SodaEconomy does not maintain a lightweight per-player transaction-count cache.
Running a journal query for every scoreboard/TAB refresh would violate the integration's
performance contract.

Dynamic placeholders such as `%sodaeconomy_balance_<currency>%` and
`%sodaeconomy_bank_<account>_balance%` are not supported. The current SodaEconomy domain model does
not expose multiple named currencies/accounts through a stable read-only contract.

## Performance and data sources

Placeholder callbacks never perform SQL, disk I/O, asynchronous waiting, `Future#get`, or
`CompletableFuture#join`. They only read concurrent/immutable in-memory presentation state.

The presentation cache is initialized and refreshed asynchronously from the official
`EconomyTransactionApi` exact-minor-unit snapshot methods. A background refresh runs every 20
seconds while the integration is active. Confirmed local wallet transactions update the wallet
cache immediately from `WalletTransactionEvent`; wallet-to-bank/bank-to-wallet records also update
the cached personal bank amount. The periodic snapshot reconciles bank administration, interest,
and changes committed by another Paper instance in a shared MySQL/MariaDB network.

| Placeholder data | Authoritative source | Placeholder-call I/O | Callback complexity |
| --- | --- | --- | --- |
| wallet balance | `EconomyTransactionApi#getStoredBalancesMinorUnits()` background snapshot + confirmed local transaction events | none | O(1) |
| bank balance | `EconomyTransactionApi#getStoredBankBalancesMinorUnits()` background snapshot + local wallet/bank transaction events | none | O(1) |
| total balance | cached wallet + cached personal bank value | none | O(1) |
| currency symbol / formatted values | current `RuntimeConfigSnapshot.CurrencySettings` | none | O(1) |
| leaderboard position | cached positions built from the same exact wallet snapshot and shared ranking rules used by `/topbalance` | none | O(1) |

The refresh task is coalesced so overlapping persistent snapshot reads are never started. Local
transaction updates are revision-protected so an older background snapshot cannot overwrite a
newer confirmed local balance.

## Formatting and precision

Raw monetary placeholders are locale-independent and fixed to two decimal places. They are derived
from SodaEconomy's canonical integer minor units rather than converting stored values through a
floating-point formatting path.

Formatted placeholders share `CurrencyFormatter` with SodaEconomy's command formatting. The active
currency symbol and prefix/suffix placement therefore remain one source of truth.

Compact values use at most two fractional digits and remove unnecessary trailing zeros:

```text
0        -> 0
999      -> 999
1000     -> 1K
1250     -> 1.25K
999999   -> 1M
1000000  -> 1M
1250000  -> 1.25M
```

The sign is preserved for negative values if such a value is ever supplied by a compatible read
source.

## `/eco reload` compatibility

The PlaceholderAPI expansion remains registered across `/eco reload`. It does not cache the
currency symbol, currency placement, leaderboard enabled flag, or leaderboard maximum. Every
configuration-sensitive resolution reads the current atomic `RuntimeConfigSnapshot`.

Therefore:

- changing `currency.symbol` updates `currency_symbol`, `balance_formatted`,
  `bank_balance_formatted`, and `total_balance_formatted` immediately after a successful
  `/eco reload`;
- changing `currency.display_after_amount` immediately changes formatted placeholder placement;
- changing `leaderboard.enabled` immediately enables/disables ranking output;
- changing `leaderboard.max-entries` immediately changes which cached positions are exposed;
- raw balance placeholders do not change when only presentation configuration changes; and
- PlaceholderAPI itself does **not** require `/papi reload` or a server restart.

If SodaEconomy rejects an invalid reload candidate, the previous runtime snapshot remains active,
so PlaceholderAPI continues returning the previous valid formatting/ranking behaviour.

### Reload compatibility matrix

| Placeholder(s) | Runtime source | Updates after `/eco reload` | Cache invalidation | `/papi reload` |
| --- | --- | --- | --- | --- |
| `currency_symbol` | current `RuntimeConfigSnapshot.CurrencySettings` | immediate | none | not required |
| `balance_formatted` | cached wallet amount + current currency settings | immediate | none | not required |
| `bank_balance_formatted` | cached bank amount + current currency settings | immediate | none | not required |
| `total_balance_formatted` | cached balances + current currency settings | immediate | none | not required |
| `rank`, `baltop_position` | cached rank + current `LeaderboardSettings.enabled/maxEntries` | immediate | none for the current Stage 27 rule set | not required |

`leaderboard.top-entries` controls the default `/topbalance` page size, not the mathematical player
position, so changing it does not require rebuilding cached positions. Stage 27 exposes no
reloadable leaderboard filter or alternate sort rule. If such a rule is added later, the
leaderboard snapshot must be invalidated/rebuilt as part of that future configuration change.

## Optional dependency lifecycle

With PlaceholderAPI installed and enabled, SodaEconomy registers its internal expansion after
storage, transaction, identity, banking, commands, and core services are initialized. The expansion
uses the plugin's own version/author metadata and returns `persist() = true` so a PlaceholderAPI
reload does not remove it.

Without PlaceholderAPI, the class-loading-safe bootstrap never loads the expansion class. No
PlaceholderAPI class is required to load SodaEconomy's core plugin classes, and no PlaceholderAPI
classes are shaded into the SodaEconomy JAR.

On SodaEconomy shutdown, the expansion is unregistered and its cache/listener/background task are
closed before the transaction/storage services are stopped.
