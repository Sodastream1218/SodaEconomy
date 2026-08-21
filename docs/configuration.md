# Configuration

The generated `plugins/SodaEconomy/config.yml` contains inline comments. This guide is the concise
reference for what each section controls and whether it can be changed with `/eco reload`.

## Reload rule

`/eco reload` is intentionally limited. It atomically reloads only settings that can change without
restarting storage or long-lived services. If a reload candidate is invalid, the previous runtime
settings remain active.

| Section / option | Default | Reload with `/eco reload`? | Notes |
| --- | --- | --- | --- |
| `storage.*` | SQLite / local defaults | No | Storage changes are startup operations and may trigger migration |
| `language` | `EN` | Yes | `EN`, `DE`, `ES` are bundled |
| `prefix.enabled` | `true` | Yes | Enables/disables the message prefix |
| `prefix.full_prefix` | SodaEconomy prefix | Yes | Uses Minecraft section-sign formatting |
| `balance` | `1000` | No | Starting wallet balance for newly created accounts |
| `max_balance` | `100000` | No | Maximum wallet balance; zero or negative values disable the maximum |
| `leaderboard.enabled` | `true` | Yes | Controls `/topbalance` and rank placeholders |
| `leaderboard.top-entries` | `10` | Yes | Default `/topbalance` result count |
| `leaderboard.max-entries` | `50` | Yes | Maximum command/rank exposure limit |
| `currency.symbol` | `$` | Yes | Display symbol or text |
| `currency.display_after_amount` | `false` | Yes | Prefix vs suffix placement |
| `integrations.vault.*` | enabled / bounded timeouts | No | Provider lifecycle is startup-only |
| `update-checker.*` | enabled / stable / 12h | Yes | Reconfigures/cancels future checks atomically |
| `banking.*` | disabled | No | Banking lifecycle and interest task are startup-only |
| `transactions.history-page-size` | `10` | No | Valid range 1–100 |
| `persistence.async.*` | enabled | No | Local persistence worker settings are startup-only |
| `debug.*` | `false` | No | Read by the corresponding runtime debug paths; restart for predictable application |

For implementation-level reload guarantees see [Safe runtime reload](config-reload.md).

## Storage

```yaml
storage:
  type: SQLITE
```

Valid values are `SQLITE`, `YAML` and `MYSQL` (case-insensitive through the storage parser). The
`MYSQL` mode is the shared JDBC mode for both MySQL and MariaDB.

### SQL connection

```yaml
storage:
  mysql:
    host: localhost
    port: 3306
    database: sodaeconomy
    user: sodaeconomy
    password: ""
```

Create a **dedicated** database user. Never use a root/superuser account for normal plugin operation.
The SQL fields are validated before connection/migration; an empty password is therefore not a usable
MYSQL configuration unless the database account genuinely permits it.

Advanced compatibility parameters:

```yaml
params:
  useSSL: false
  autoReconnect: false
  allowPublicKeyRetrieval: true
  connectTimeout: 5000
  socketTimeout: 30000
  tcpKeepAlive: true
```

`autoReconnect` is retained for legacy config compatibility but is deliberately not used to hide the
outcome of an active transaction. Read [Database & networks](database.md) before changing these values.

### Migration safety

```yaml
storage:
  migration:
    confirm-single-server-mysql: false
    dry-run: false
```

- `confirm-single-server-mysql`: required safety acknowledgement for MYSQL→YAML/SQLite migration.
  Set it only when exactly one server is using the SQL database and every other network instance is stopped.
- `dry-run`: reads/logs source snapshot counts without importing or changing the active storage marker.

## Language and prefix

```yaml
language: "EN"
prefix:
  enabled: true
  full_prefix: "§8[§bSodaEconomy§8] §r"
```

Bundled language codes: `EN`, `DE`, `ES`. The active language file is also reloaded by `/eco reload`.

## Economy limits

```yaml
balance: 1000
max_balance: 100000
```

Money is normalized to SodaEconomy's two-decimal minor-unit representation. `balance` must be a valid
non-negative starting amount. `max_balance` values of zero or less disable the wallet maximum.

## Leaderboard

```yaml
leaderboard:
  enabled: true
  top-entries: 10
  max-entries: 50
```

All three options are reload-safe. `top-entries` is the default page size; `max-entries` caps a
requested `/topbalance <limit>` and the rank positions exposed by PlaceholderAPI.

## Currency

```yaml
currency:
  symbol: "$"
  display_after_amount: false
```

Both options are reload-safe and are shared by commands, Vault formatting and formatted
PlaceholderAPI values.

## Vault

```yaml
integrations:
  vault:
    enabled: true
    operation-timeout-millis: 3000
    warn-after-millis: 100
```

- `enabled`: register the Vault economy provider when Vault is installed.
- `operation-timeout-millis`: maximum cancellable wait before a Vault mutation begins durable execution.
- `warn-after-millis`: rate-limited slow-call warning threshold; `0` disables the warning.

These settings require restart. See [Vault](vault.md).

## Update checker

```yaml
update-checker:
  enabled: true
  source: "github"
  channel: "stable"
  check-on-startup: true
  check-interval-hours: 12
  notify-console: true
  notify-admins-on-join: true
  notify-admins-per-session: true
  connect-timeout-seconds: 4
  read-timeout-seconds: 6
```

All update-checker values are reload-safe. `source` is `github` in v1.0.0. Valid channels are
`stable`, `rc`, `beta`, `alpha`; a channel also accepts more mature releases. Check interval range is
1–168 hours, connection timeout 1–30 seconds and request timeout 1–60 seconds.

See [Update checker](update-checker.md) for privacy and failure behaviour.

## Banking

```yaml
banking:
  enabled: false
  interest_enabled: false
  interest_rate: 0.05
  interest_interval: 60
  max_interest_per_account: 0.0
```

- `enabled`: enables the native personal-bank feature.
- `interest_enabled`: enables scheduled interest when banking is enabled.
- `interest_rate`: decimal rate (`0.05` = 5%).
- `interest_interval`: interest interval in minutes.
- `max_interest_per_account`: maximum credit per account/interval; `0` disables the cap.

Banking settings are startup-only.

## Transaction history

```yaml
transactions:
  history-page-size: 10
```

Valid range: 1–100 entries per page. Restart after changing it.

## Local asynchronous persistence

```yaml
persistence:
  async:
    enabled: true
    initial_retry_delay_millis: 100
    maximum_retry_delay_millis: 5000
    shutdown_warning_interval_seconds: 5
    queue_capacity: 10000
    warning_threshold_percent: 80
    maximum_retry_attempts: 8
    recovery_probe_interval_seconds: 30
    shutdown_timeout_seconds: 30
```

This worker is used for local YAML/SQLite write-behind persistence. MYSQL mode stays database-
authoritative and does not use this queue. The recovery WAL represents unresolved accepted local
writes across a process crash.

These are advanced startup-only settings. Keep the defaults unless troubleshooting a known operational
constraint. See [Local persistence recovery](local-persistence-recovery.md).

## Debugging

```yaml
debug:
  storage: false
  commands: false
  banking: false
  integrations: false
```

Enable only the subsystem needed for diagnosis. `debug.integrations` also exposes technical causes for
update-check failures that are intentionally summarized during normal operation.
