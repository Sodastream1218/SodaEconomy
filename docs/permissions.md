# Permissions

SodaEconomy keeps player, audit, administration, banking and update permissions separate so server
owners can delegate access without giving every operator every economy control.

## Player permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `sodaeconomy.topbalance` | `true` | `/topbalance` |
| `sodaeconomy.bank.use` | `true` | Access to `/bank`; banking must also be enabled |
| `sodaeconomy.history.self` | `true` | `/eco history [page]` for the sender's own history |

`/balance` and `/pay` do not define additional permission nodes in `plugin.yml`.

## Audit / moderator permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `sodaeconomy.history.others` | `op` | View another known player's history |
| `sodaeconomy.transaction` | `op` | View an immutable transaction by UUID |
| `sodaeconomy.audit` | `op` | View player transaction aggregates |
| `sodaeconomy.stats` | `op` | View server/player economy statistics |
| `sodaeconomy.rollback` | `op` | Attempt a reversing rollback transaction |

Rollback is a mutation permission and should normally be restricted more tightly than read-only audit
permissions.

## Administrator permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `sodaeconomy.admin` | `op` | `/eco set`, `give`, `remove`, `reset`, `balance`, `payall`, `multipay`; also satisfies the normal `/eco` history/audit/stats/reload checks |
| `sodaeconomy.admin.reload` | `op` | `/eco reload` without needing the broader `sodaeconomy.admin` permission |
| `sodaeconomy.bank.admin` | `op` | `/bank setbalance` in addition to `sodaeconomy.bank.use` |
| `sodaeconomy.admin.update` | `op` | `/eco version [check]` and administrator update notifications |

### Important separation

`sodaeconomy.admin.update` is deliberately independent from SodaEconomy's internal `/eco` admin
umbrella check. `sodaeconomy.bank.admin` is also a separate banking permission. Operators normally
have all of these because Bukkit grants operators broad permissions, but a permissions plugin should
assign the exact nodes required for non-operator staff roles.

## Suggested role split

- **Players:** `sodaeconomy.topbalance`, `sodaeconomy.bank.use`, `sodaeconomy.history.self`
- **Moderators/auditors:** add history-others, transaction, audit and stats
- **Senior administrators:** add rollback and `sodaeconomy.admin`
- **Operations staff:** add `sodaeconomy.admin.reload` and `sodaeconomy.admin.update`
- **Bank administrators:** add `sodaeconomy.bank.admin`

See [Commands](commands.md) for console behaviour and syntax.
