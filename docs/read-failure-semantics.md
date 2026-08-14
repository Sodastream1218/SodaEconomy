# Persistent read failure semantics

SodaEconomy distinguishes **valid economy data** from **failure to read economy data**. This is
important because a real `0.00` balance or an actually empty leaderboard must never be confused with
a database outage, corrupted read, stopped service, or other persistence failure.

## Contract

Read operations that can access persistence follow these rules:

| Situation | Result |
| --- | --- |
| stored wallet exists with `0.00` | successful read containing `0.00` |
| stored wallet does not exist | successful stored-balance read containing `0.00` where documented |
| economy genuinely has no stored accounts | successful empty snapshot |
| transaction history genuinely has no matching records | successful empty page |
| backend/persistent read fails | exceptional completion / unavailable command result |
| transaction service is stopping | exceptional completion rather than invented economy data |

The public asynchronous `EconomyTransactionApi` read surfaces therefore do not translate persistent
exceptions into zero balances, empty maps, empty history pages, or zeroed statistics.

## Commands

Read-only command paths such as `/balance`, `/topbalance`, bank balance views, `/eco balance`,
`/eco stats`, `/eco history`, `/eco transaction`, and `/eco audit` display the localized
`economy-read-unavailable` message when the required backend read cannot be completed.

Mutation failures continue to use transaction-specific error handling; this stage does not convert
write failures into read-unavailable messages.

## PlaceholderAPI

PlaceholderAPI never performs persistent I/O in the placeholder callback. The background cache keeps
separate wallet and bank snapshot availability.

- On a successful refresh, the corresponding cached snapshot advances.
- On a failed refresh, that source keeps its previous successful snapshot.
- A valid successful empty snapshot is still allowed to clear old cached entries.
- Before the first successful required snapshot, player-value placeholders return `-`.
- Once a valid snapshot exists, temporary backend failure does not cause scoreboard/TAB values to
  jump to `0`.

This is intentionally stale-on-error presentation behaviour: preserving the last known authoritative
value is safer and less misleading than representing backend failure as real economy state.

## Vault note

Vault's legacy synchronous balance-read API returns primitive values and does not provide an error
channel comparable to `CompletableFuture`. SodaEconomy's supported asynchronous developer API and
its own commands therefore carry the stronger explicit read-failure semantics. Vault mutation paths
remain unchanged and still return Vault `EconomyResponse` failures where the Vault API supports a
result object.

## Logging

The transaction layer logs persistent read failures with the operation that failed. Normal command
users receive a concise localized unavailable message rather than Java exception details.
