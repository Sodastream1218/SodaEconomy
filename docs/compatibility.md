# Cross-version compatibility

SodaEconomy is designed to ship as one Java 17 plugin JAR for modern Paper/Purpur servers. This
document defines the supported target range and the checks that must pass before a release is
advertised as compatible with a new Minecraft/Paper line.

## Supported server range

Officially supported for the 1.0 release candidate:

- Paper/Purpur 1.20.2 and newer within the modern Paper API line
- Java 17-compatible plugin bytecode running on the Java runtime required by the target server
- YAML, SQLite and MySQL storage on the same plugin JAR

The current `plugin.yml` intentionally uses:

```yaml
api-version: 1.20
```

That makes Paper 1.20 the declared API floor. The code is compiled against
`io.papermc.paper:paper-api:1.20.2-R0.1-SNAPSHOT`, so newer server support must be verified by
runtime smoke tests rather than by directly calling newer Paper APIs.

## Not currently supported

Do not advertise support for these targets without a separate audit and runtime test pass:

- Paper/Purpur below 1.20.2
- Spigot/Bukkit-only servers
- Folia
- Java runtimes below 17
- Legacy Minecraft 1.16.x or older

The plugin uses Java 17 language/runtime APIs (`record`, `Stream#toList`, `StackWalker`) and the
modern Adventure-based Paper messaging stack. Supporting substantially older servers would require a
separate legacy branch or compatibility module rather than lowering `api-version` blindly.

## Build compatibility rules

Maven and Gradle must both produce Java 17 class files without linking against newer JDK APIs:

- Maven uses `maven-compiler-plugin` with `<release>${maven.compiler.release}</release>`.
- Gradle uses `JavaCompile.options.release.set(17)`.
- The Paper API dependency remains compile-only/provided and must not be shaded into the plugin.
- MySQL Connector/J and sqlite-jdbc remain shaded because they are runtime storage dependencies.

Do not replace the `release` compiler setting with only `source`/`target`; that would allow a build
running on Java 21 or Java 25 to accidentally reference APIs that are unavailable on Java 17.

## Automated build matrix

The GitHub Actions verification workflow runs the full Gradle test suite, MySQL integration tests
and shaded-JAR build on both Java 17 and Java 21. Java 17 proves the released bytecode floor remains
valid; Java 21 covers the recommended runtime for Paper 1.20 through 1.21.x. Newer Paper 26.x server
lines must still be smoke-tested on their required Java runtime before compatibility is advertised.

## Runtime smoke-test matrix

Before a public compatibility claim, test the built shaded JAR on a clean server for each target
line below. Use the Java runtime required by that server line.

| Target | Required result |
| --- | --- |
| Paper 1.20.2 | Plugin loads, all commands register, YAML works |
| Paper 1.20.6 | Plugin loads, `/balance`, `/pay`, `/eco reload` work |
| Paper 1.21.1 | Plugin loads, SQLite works, restart keeps balances |
| Paper 1.21.8/1.21.11 | Plugin loads, MySQL works, API service registers |
| Latest Paper 26.x line | Plugin loads on the server-required Java runtime |

The smoke test for every line must cover:

1. Server startup with a fresh `plugins/SodaEconomy` folder.
2. `/balance`, `/pay`, `/topbalance`, `/bank`, `/eco give`, `/eco remove`, `/eco reload`.
3. Storage restart test for YAML and SQLite.
4. MySQL startup and one transaction against an isolated MySQL database.
5. Clean shutdown without persistence warnings.

## MySQL network verification

Cross-version support does not replace the existing integration tests. Before release, also run:

```bash
SODAECONOMY_TEST_MYSQL=true ./mvnw verify
```

against an isolated MySQL test database. The integration suite verifies schema version 6,
`sodaeconomy_runtime_state`, maintenance leases, idempotency, and two-instance concurrency.

## Compatibility decision log

- Keep one JAR for the 1.20.2+ modern Paper/Purpur range.
- Keep Java 17 bytecode to remain usable on older modern hosts while still running on newer Java
  runtimes.
- Do not introduce NMS, CraftBukkit internals, Paper Lifecycle API, Paper Command API, registries,
  packets, or item data-component APIs without updating this document and adding a compatibility
  adapter or version gate.
- Do not claim Folia support until scheduler, event, and storage callbacks are audited for Folia's
  region-thread model.


## Geyser and Floodgate

The economy model is UUID-based and does not depend on client protocol details. Floodgate is an
optional soft dependency used only by the central player identity service to classify Bedrock
players. No Floodgate classes are linked into the plugin JAR, and the plugin must start normally
when Floodgate is absent.

Before advertising crossplay support for a network, smoke-test Java-to-Bedrock and
Bedrock-to-Java payments, bank operations, leaderboard names and restarts. Proxy setups must expose
Floodgate API data to the backend if `BEDROCK` classification is required there. See
`docs/player-identities.md`.

## Optional integrations

Vault and Floodgate remain optional soft dependencies. The released JAR does not shade VaultAPI and
loads Vault-specific provider classes only after the Vault plugin is detected. Cross-version smoke
tests should therefore cover both startup without Vault and provider discovery with the current
Vault server plugin.
