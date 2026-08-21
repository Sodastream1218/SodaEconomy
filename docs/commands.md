# Commands

This reference reflects the commands registered and handled by SodaEconomy v1.0.0. Amounts use the
configured currency only for display; command input is a decimal amount such as `10`, `10.50` or
`1250.25`.

## Player economy commands

| Command | Permission | Console | Description |
| --- | --- | --- | --- |
| `/balance` | none | No | Show your wallet balance |
| `/pay <player> <amount>` | none | No | Pay an online player from your wallet |
| `/topbalance [limit]` | `sodaeconomy.topbalance` | Yes | Show the richest stored wallet accounts |

`/pay` requires the recipient to be online. `/topbalance` uses the configured default and maximum
entry limits.

## Banking

Banking must be enabled in `config.yml`. The `/bank` command itself requires `sodaeconomy.bank.use`.

| Command | Additional permission | Console | Description |
| --- | --- | --- | --- |
| `/bank balance` | none | No | Show your bank balance |
| `/bank balance <player>` | none | Yes | Show a known player's bank balance |
| `/bank bal [player]` | none | Same as `balance` | Alias for the balance subcommand |
| `/bank deposit <amount>` | none | No | Move wallet money into your bank |
| `/bank withdraw <amount>` | none | No | Move bank money back to your wallet |
| `/bank setbalance <player> <amount>` | `sodaeconomy.bank.admin` | Yes | Set a known player's bank balance |

The native bank is a personal savings-account model. It is not Vault's named organisation-bank API.

## `/eco` audit and read commands

| Command | Permission | Console | Description |
| --- | --- | --- | --- |
| `/eco history [page]` | `sodaeconomy.history.self` | No | Show your own wallet transaction history |
| `/eco history <player> [page]` | `sodaeconomy.history.others` | Yes | Show history for a known identity |
| `/eco transaction <transactionId>` | `sodaeconomy.transaction` | Yes | Show one immutable transaction record |
| `/eco rollback <transactionId>` | `sodaeconomy.rollback` | Yes | Create a reversing transaction when eligible |
| `/eco audit <player>` | `sodaeconomy.audit` | Yes | Show a player's transaction aggregate |
| `/eco stats [player]` | `sodaeconomy.stats` | Yes | Show server analytics or one player's statistics |

`/eco history` and identity-based audit/statistic lookups can resolve known offline identities from the
persistent identity registry. Rollback never deletes or edits the original record; it creates a new
linked transaction.

## `/eco` administration

| Command | Permission | Console | Description |
| --- | --- | --- | --- |
| `/eco set <player> <amount>` | `sodaeconomy.admin` | Yes | Set an **online** player's wallet balance |
| `/eco give <player> <amount>` | `sodaeconomy.admin` | Yes | Credit an **online** player's wallet |
| `/eco remove <player> <amount>` | `sodaeconomy.admin` | Yes | Debit an **online** player's wallet |
| `/eco reset <player>` | `sodaeconomy.admin` | Yes | Reset an **online** player to the configured starting balance |
| `/eco balance <player>` | `sodaeconomy.admin` | Yes | Show an **online** player's wallet balance |
| `/eco payall <amount>` | `sodaeconomy.admin` | Yes | Credit every currently online player |
| `/eco multipay <amount> <player1> <player2> ...` | `sodaeconomy.admin` | Yes | Credit selected online players as one auditable batch |

The console may execute these commands, but player targets for the mutation/balance commands above are
intentionally resolved from online players.

## Configuration and update information

| Command | Permission | Console | Description |
| --- | --- | --- | --- |
| `/eco reload` | `sodaeconomy.admin.reload` | Yes | Atomically reload the documented runtime-safe settings |
| `/eco version` | `sodaeconomy.admin.update` | Yes | Show installed version and cached update status |
| `/eco version check` | `sodaeconomy.admin.update` | Yes | Start a manual asynchronous GitHub release check |

Manual update checks have a global 60-second cooldown. The command never downloads or installs an
update.

## Help and tab completion

Running `/eco` without a subcommand shows only the help entries the sender is allowed to use.
Known-player name suggestions come from SodaEconomy's identity service without synchronous database
queries during tab completion.

See [Permissions](permissions.md) for defaults and the special `sodaeconomy.admin` umbrella behaviour.
