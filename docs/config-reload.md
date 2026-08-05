# Safe runtime configuration reload

`/eco reload` deliberately reloads only settings and files that can be replaced without restarting
storage, services, repositories, database connections or scheduled tasks.

## Reloadable settings

```yaml
language: "EN"

prefix:
  enabled: true
  full_prefix: "§8[§bSodaEconomy§8] §r"

leaderboard:
  enabled: true
  top-entries: 10
  max-entries: 50

currency:
  symbol: "$"
  display_after_amount: false
```

The command requires `sodaeconomy.admin.reload`; operators and users with
`sodaeconomy.admin` are also accepted by the existing command permission hierarchy.

## Atomic behaviour

The persisted `config.yml` is parsed into a temporary `YamlConfiguration`. The reloadable config
values and the requested language code are validated first. The active language file is then read
into a temporary language snapshot and validated against bundled defaults. Only after both
candidates are valid are the runtime config snapshot and language snapshot published. If the file
is missing, malformed, unreadable, contains an invalid reloadable value, or the selected language
file is invalid, the previous snapshots remain active and no partial update is visible.

The plugin intentionally does **not** call Bukkit's `reloadConfig()`. Startup-only values therefore
retain their original in-memory configuration until the next full server restart, even when those
values were edited in the same file before `/eco reload` was executed.

## Deliberately not reloaded

The command never changes or restarts:

- YAML, SQLite or MySQL storage
- database connections, schema or migrations
- transaction, bank or persistence services
- async queue and recovery settings
- banking or interest scheduling
- starting balance or maximum balance
- debug settings
- any other configuration path

## Runtime consumers

- `LanguageManager` reads message text from the current language snapshot and prefix settings
  from the current runtime config snapshot for every future formatted message.
- `EconomyManager` reads currency settings from the same snapshot for every future amount format.
- `TopBalanceCommand` reads enabled/default/maximum leaderboard values when each command starts.

Editing `plugins/SodaEconomy/language/messages_<code>.yml` and then running `/eco reload` updates
future messages immediately when the file is valid. Existing already-created Adventure/Text
components are not retroactively changed.

This keeps repeated reloads lightweight and avoids replacing command, storage or service objects.


## Vault integration settings

Vault enablement, timeout, and slow-call thresholds are startup-only and are never changed by
`/eco reload`. Currency formatting and localized/prefixed error messages still use the live runtime
snapshots after a safe reload.
