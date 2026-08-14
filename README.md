# SodaEconomy

SodaEconomy is a Paper/Purpur economy plugin with YAML, SQLite, and MySQL/MariaDB storage backends.
Wallet balances remain in their dedicated balance store for fast reads. Every wallet mutation is
also written to an immutable transaction journal, so normal balance reads never replay history.


## License

SodaEconomy is **source available**, not open source. It is licensed under the
Apache License 2.0 subject to the Commons Clause License Condition v1.0 and the
express permissions in [`LICENSE`](LICENSE).

Use on monetized Minecraft servers and networks is expressly permitted,
including revenue from ranks, shops, crates, donations, advertising,
subscriptions, and server access. General Minecraft hosting may also include
SodaEconomy as one component of a broader offering.

The restriction applies to commercializing SodaEconomy itself: the JAR or
source code may not be sold, placed behind a paid download, rebranded as a paid
economy plugin, or offered as a substantially equivalent commercial substitute
without prior written permission.

Read the complete documents:

- [`LICENSE`](LICENSE) — authoritative software terms
- [`LICENSE-FAQ.md`](LICENSE-FAQ.md) — practical usage examples
- [`TRADEMARKS.md`](TRADEMARKS.md) — project-name and branding rules
- [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) — dependency attribution

## Compatibility

SodaEconomy is built as a Java 17 plugin for modern Paper/Purpur servers. The current release
candidate targets Paper/Purpur 1.20.2 and newer within the modern Paper API line while keeping
`api-version: 1.20` and compiling against Paper API 1.20.2. Newer Paper lines must be verified by
runtime smoke tests before being advertised as supported.

The project does not currently claim support for Folia, Bukkit/Spigot-only servers, Paper below
1.20.2, or Java runtimes below 17. See `docs/compatibility.md` for the compatibility matrix, Java
runtime notes, Java 17/21 verification matrix, and the required server smoke tests.

## Transaction API

The plugin registers `EconomyTransactionApi` through Bukkit's `ServicesManager` after its storage
has initialized successfully. This interface is the supported public integration boundary. Other
plugins must obtain and use it instead of mutating `EconomyManager`, `Storage`,
`WalletTransactionStore`, or concrete storage implementations directly. Those lower-level classes
are implementation details and can bypass the journal, runtime cache, and transaction events.

```java
RegisteredServiceProvider<EconomyTransactionApi> registration = Bukkit.getServicesManager()
        .getRegistration(EconomyTransactionApi.class);
if (registration == null) {
    return;
}

EconomyTransactionApi economy = registration.getProvider();
economy.transfer(sourceId, targetId, 25.00D, TransactionOrigin.api("ExamplePlugin"),
        TransactionRequestOptions.idempotent("Quest reward payment", Map.of("quest", "starter"),
                "starter-quest:" + sourceId))
        .thenAccept(result -> {
            if (result.isSuccessful()) {
                // The balance change and journal record are durably committed.
            }
        });
```

The API is asynchronous. Do not access Bukkit player objects from completion callbacks without
scheduling back to the Paper main thread. `TransactionResult` contains the immutable record on a
committed or persistable failed operation and a machine-readable `TransactionFailureReason`.

`getStoredBalance(UUID)` reads a wallet balance asynchronously without creating an account or
awarding a starting balance; it returns `0.0` when no account is stored. Exact read-only snapshot
methods expose wallet and bank balances in canonical minor units for high-frequency presentation
integrations without floating-point conversion. Use an idempotency key for
operations that may be retried by the calling plugin. The key must identify one logical operation
and must not be reused for unrelated payments.

## Player identity API and optional Floodgate support

SodaEconomy maintains a central, UUID-based player identity registry for commands, transaction
history and leaderboards. `PlayerIdentityApi` is registered through Bukkit's `ServicesManager` as a
read-only integration surface. Unknown names never create synthetic offline accounts.

Known names and platform types are persisted by every storage backend and migrate together with the
rest of the economy snapshot. MySQL uses schema version 6 and SQLite uses schema version 4 for the
identity registry. Leaderboards batch-resolve persisted names so another backend can display a
player correctly even when that player has never joined the local instance.

Floodgate is an optional soft dependency. When its API is available, SodaEconomy classifies
Floodgate UUIDs as `BEDROCK`; without Floodgate, the plugin remains fully functional. The economy
account key is always the Bukkit/Floodgate UUID. See `docs/player-identities.md` for threading,
collision and proxy-network details.

## Public API boundary and compatibility

`EconomyTransactionApi` is the supported integration contract for balance mutations, exact read-only
balance snapshots, and transaction history. `PlayerIdentityApi` is the supported read-only contract for central UUID/name
resolution. `SodaEconomy` managers, storage contracts, concrete storage classes, and the concrete
service implementations are internal runtime infrastructure. They are retained only for legacy
compatibility during the transition to the public APIs and must not be used by new plugins.

The public API uses asynchronous operations so integrations receive a durable result rather than a
local cache prediction. It is intentionally the only integration surface that preserves validation,
immutable history, rollback eligibility, statistics, and transaction events together. Low-level
storage mutations are runtime-guarded and may throw `UnauthorizedStorageAccessException` when used
from outside SodaEconomy's internal storage/transaction packages.

## Asynchronous persistence

By default, `persistence.async.enabled` keeps runtime wallet and bank balances in a protected
in-memory cache for local YAML/SQLite installations. One dedicated `SodaEconomy-Persistence` worker
writes accepted operations to the authoritative backend in strict acceptance order. MySQL/MariaDB
continues using its database-authoritative path and does not use the local write-behind queue.

Before a queued local mutation is reported as accepted, SodaEconomy appends one compact recovery
WAL record and forces it to disk. The record contains only the unresolved mutation's absolute
post-write account state plus its immutable wallet transaction when applicable. Long-term
transaction history remains in YAML/SQLite and is **not** copied into recovery storage on every
mutation. Once persistence catches up, committed recovery entries are compacted away and a fully
drained queue leaves no recovery WAL payload.

The queue remains bounded, retries a failed head write with capped exponential backoff, and never
lets a later write overtake it. A normal plugin/server shutdown drains only up to the configured
shutdown deadline; any remaining accepted local mutation stays represented by the recovery WAL for
startup reconciliation. Audit/history and statistics API queries remain asynchronous; Bukkit
messages and events are still scheduled only on the Paper main thread.

`EconomyTransactionApi` futures preserve authoritative durable-completion semantics: they complete
after the queued ledger record has reached the backend. Synchronous gameplay/compatibility methods
may return after the local recovery durability barrier so their accepted state survives an
immediate process crash within the realistic guarantees of Java and the host filesystem.

See [`docs/local-persistence-recovery.md`](docs/local-persistence-recovery.md) for the recovery state
machine, corruption behavior, legacy-format upgrade path and opt-in stress tests.

## Ledger and rollback

Each wallet record contains a UUID, UTC timestamp (millisecond precision), type, status, origin,
source/target player UUIDs, requested and applied amount in minor units, wallet snapshots, reason,
metadata, and optional rollback/batch identifiers.

Rollbacks create a new `ROLLBACK` record linked to the original record; records are never deleted
or modified. A successful rollback is unique per original transaction. A rollback can fail safely,
for example when the recipient already spent the funds or a configured wallet maximum would be
exceeded.

Wallet-to-bank and bank-to-wallet moves are committed atomically with the wallet-side journal
record. Pure bank administration and interest remain outside the wallet ledger until bank-ledger
records are introduced, but they are coordinated by the same service lock.

## Storage migration

When changing storage type, `StorageSnapshot` migrates wallet balances, bank balances, wallet
journal records, and known player identities together. Existing pre-ledger balances are preserved and receive one
`LEGACY_OPENING_BALANCE` baseline record when the journal is first enabled; older history cannot
be reconstructed retrospectively.

## Statistics and audit queries

`EconomyTransactionApi#getStatistics()` returns current wallet circulation, average balance,
richest account, journal status counts, and wallet money sources/sinks. `getAnalytics()` adds the
largest successful wallet transaction and transfer volume; `getPlayerStatistics(UUID)` supplies a
participant's audit aggregate. Transfers and wallet-bank moves do not count as sources or sinks
because they only relocate existing funds.

Use `findTransactions(TransactionQuery)` for bounded, paginated audit/history queries by player,
transaction ID, type, status, or time range. The SQL backends index common participant and time
queries; YAML is intended for smaller installations.

## In-game ledger commands

`/eco history [page]` lets players view their own wallet history. Administrators, or users with
`sodaeconomy.history.others`, can use `/eco history <player> [page]` for another player.

The following administrative commands are backed by the same immutable journal and transaction
service:

- `/eco transaction <transactionId>` — complete journal record details
- `/eco rollback <transactionId>` — creates an atomic reversing entry; it never deletes history
- `/eco audit <player>` — transaction counts, received/sent totals, net change, and time range
- `/eco stats [player]` — server-wide analytics or a player's balance and audit statistics

The additional permissions are `sodaeconomy.transaction`, `sodaeconomy.rollback`,
`sodaeconomy.audit`, and `sodaeconomy.stats` (all default to operators). `sodaeconomy.history.self`
defaults to all players. `sodaeconomy.admin` remains a compatibility override for every `/eco`
administrative operation.

Configure `transactions.history-page-size` in `config.yml` to choose 1–100 rows per history page.
Player statistics read existing data without creating a new wallet account.

## Development verification

```text
./mvnw verify
```

The test suite includes unit tests for the transaction service and shared wallet-ledger storage
contracts for YAML and SQLite. MySQL equivalents run when the CI MySQL test environment is
configured.

## Shared MySQL maintenance gate

Storage migrations involving MySQL use a database-backed mutation gate. Every MySQL wallet or bank
mutation takes a shared row lock on the gate for the lifetime of its ACID transaction. Starting a
migration takes the exclusive row lock, waits for already active mutations to finish, and then marks
the gate as active. Other Paper/Purpur instances reject new mutations while the migration snapshot is
being validated and imported. The migration owner may replace the snapshot, and activation happens
only after the gate was released successfully.

The gate is stored in `sodaeconomy_runtime_state` (current MySQL schema version 6) and requires no service besides
the configured MySQL database. Reads remain available during maintenance. An abnormal release failure
is logged as a severe operational condition because writes intentionally remain blocked rather than
risking a split-brain migration.

## Release hardening stage 5

The optional JDBC integration suite runs with MariaDB Connector/J against both MySQL and MariaDB and includes explicit two-instance network-concurrency tests. They verify that independent plugin storage instances sharing one database cannot overspend the same wallet and that one idempotency key produces exactly one durable transaction and one balance mutation even when submitted concurrently from different instances.

Run these tests against an isolated MySQL or MariaDB database by setting `SODAECONOMY_TEST_MYSQL=true` and the credentials documented in `src/test/README.md`, then execute `./mvnw verify`.

## Safe config reload

Administrators can use `/eco reload` (permission `sodaeconomy.admin.reload`) to atomically reload
only the runtime-safe language selection, active language file, prefix, leaderboard and currency-display settings. Storage, database,
banking, persistence, debug and all other settings remain unchanged until a full server restart. Invalid or malformed configuration never partially replaces the active
settings. See `docs/config-reload.md` for the exact supported paths and failure behaviour.

## Optional PlaceholderAPI support

When PlaceholderAPI is installed, SodaEconomy automatically registers a native internal expansion
for the `%sodaeconomy_...%` namespace. PlaceholderAPI remains a soft dependency and is never shaded
into the SodaEconomy JAR. No separate expansion download or configuration is required.

Core placeholders include wallet, bank and combined balances, formatted/compact balance variants,
the live currency symbol, and cached leaderboard position. Placeholder callbacks never perform
synchronous SQL or disk I/O; persistent snapshots are refreshed asynchronously in the background.

Currency formatting and ranking limits use SodaEconomy's current runtime configuration, so a
successful `/eco reload` is reflected automatically without `/papi reload`. See
[`docs/placeholderapi-integration.md`](docs/placeholderapi-integration.md) for the complete placeholder
contract, exact fallback behaviour, data sources, performance characteristics and intentionally
unsupported placeholders.

## Optional Vault support

When Vault is installed, SodaEconomy can register as its economy provider. The adapter uses the
same durable TransactionService, journal, audit, statistics and rollback paths as native commands;
it never writes directly to storage. Vault is optional and can be disabled under
`integrations.vault`. See `docs/vault-integration.md` for configuration and consistency details.

## JDBC driver delivery

MySQL and MariaDB connections use MariaDB Connector/J loaded through Paper libraries; the driver is not embedded in the SodaEconomy JAR. Existing `storage.type: MYSQL` configurations remain valid. See `docs/jdbc-driver-migration.md`.
