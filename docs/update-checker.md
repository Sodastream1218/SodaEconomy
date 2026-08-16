# Optional update checker

SodaEconomy's update checker is an informational utility. It tells administrators that a newer
official SodaEconomy release exists; it never downloads, replaces or installs a plugin JAR.

## Release source

The v1 implementation uses the public GitHub Releases API for:

`Sodastream1218/SodaEconomy`

It requests the published releases list over HTTPS and reads only the release tag, prerelease/draft
flags and the public HTML release-page URL. Draft releases, malformed tags and unsupported version
formats are ignored. No authentication token is configured or required for the public repository.

## Configuration

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

All update-checker values are reload-safe with `/eco reload`.

- `enabled`: disables all future network checks when false.
- `source`: currently only `github` is supported.
- `channel`: allowed release maturity (`stable`, `rc`, `beta`, `alpha`).
- `check-on-startup`: schedules the first asynchronous check about one minute after a successful plugin start.
- `check-interval-hours`: automatic-check period, from 1 to 168 hours.
- `notify-console`: prints a new-version notice once for a discovered version.
- `notify-admins-on-join`: notifies eligible online/joining administrators when an update is cached.
- `notify-admins-per-session`: suppresses repeated player notices during the same login session.
- `connect-timeout-seconds`: HTTPS connection timeout, from 1 to 30 seconds.
- `read-timeout-seconds`: Java HTTP request/response timeout, from 1 to 60 seconds.

A reload validates the complete candidate first. Invalid values do not partially replace the active
checker settings. Turning the checker off cancels its future automatic schedule immediately. An
already-running check is invalidated and cancellation is attempted; its stale result is never published.

## Channels and versions

The selected channel also accepts more mature releases:

| Selected channel | Accepted releases |
| --- | --- |
| `stable` | stable |
| `rc` | rc, stable |
| `beta` | beta, rc, stable |
| `alpha` | alpha, beta, rc, stable |

The parser handles normal semantic versions and SodaEconomy's supported prerelease families, for
example `1.0.0`, `v1.0.0`, `1.0.0-alpha.1`, `1.0.0-beta.2` and `1.0.0-rc.1`. Development labels such
as `1.0.0-SNAPSHOT`, `1.0.0-dev`, `dev` or `unknown` do not generate potentially misleading update warnings.

## Commands and permission

`/eco version` displays the installed version, cached latest version, status, channel and source.
When a public release page is available, players receive a clickable link and console senders receive
the plain URL.

`/eco version check` starts a new asynchronous check. Manual requests have a global 60-second
cooldown so repeated commands cannot turn the checker into a GitHub request loop.

Both operations and join notifications require the dedicated permission:

```text
sodaeconomy.admin.update
```

It defaults to `op`.

## Runtime and failure behaviour

- HTTP uses Java 17's asynchronous `HttpClient`.
- No update request is awaited during plugin enable.
- Only one update request is active at a time.
- Results are cached; automatic checks use the configured hour interval.
- Failed requests do not disable SodaEconomy or any economy feature.
- Failure logs are rate-limited. Technical causes are included only when integration debugging is enabled.
- GitHub response handling runs independently of Bukkit state. Finished notifications are dispatched
  back to the Paper main thread with one-shot scheduler tasks.
- The release-page URL is shown to administrators. Asset/JAR URLs are not downloaded or installed.

## Privacy

SodaEconomy does not transmit player names, player UUIDs, balances, transactions, server names,
plugin lists, database credentials/data or a generated telemetry identifier. The checker sends only
a normal unauthenticated HTTPS GET request to GitHub's public API with generic GitHub-required HTTP
headers. As with any outbound HTTPS request, the remote service and network infrastructure can see
normal connection metadata such as the source IP address.

Disable `update-checker.enabled` if the server must not perform this outbound request.
