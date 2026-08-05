# Player identity architecture

SodaEconomy uses one central identity layer for UUID/name resolution. Commands, leaderboards and
future platform bridges must not call `Bukkit.getOfflinePlayer(String)` or implement their own
name caches.

## Public API

`PlayerIdentityApi` is registered through Bukkit's `ServicesManager` and is also exposed by
`SodaEconomy#getPlayerIdentityApi()`. The API is read-only. Identity writes are owned by
SodaEconomy and originate from trusted server events.

```java
RegisteredServiceProvider<PlayerIdentityApi> registration = Bukkit.getServicesManager()
        .getRegistration(PlayerIdentityApi.class);
if (registration == null) {
    return;
}

PlayerIdentityApi identities = registration.getProvider();
identities.resolve(playerId).thenAccept(identity -> identity.ifPresent(value -> {
    String lastKnownName = value.lastKnownName();
    PlayerType type = value.playerType();
}));
```

`findOnlinePlayer(...)` and `suggestKnownNames(...)` touch Bukkit player state and must be called on
the server thread. Persistent `resolve(...)` and `resolveDisplayNames(...)` operations are
asynchronous. Unknown names return an empty result; they never create a synthetic `OfflinePlayer`
or economy account.

## Registry model

Every persisted record contains:

- UUID
- last known server-visible player name
- NFKC-normalized, lower-case name for case-insensitive lookup
- last known login timestamp
- platform type: `JAVA`, `BEDROCK` or `UNKNOWN`

Name collisions remain explicit. If one normalized name maps to multiple UUIDs, name resolution
returns no unique result rather than selecting an arbitrary account.

The process-local `KnownPlayerRegistry` is thread-safe and loaded on demand. Persistent storage is
authoritative for offline resolution and batch leaderboard rendering, so a second Paper instance
can observe renames without relying indefinitely on stale node-local data. Leaderboards use one
batch lookup rather than one query per account.

## Persistence and migrations

The identity registry is part of the existing storage architecture:

- YAML: `player_identities` section in the economy storage file
- SQLite: `player_identities` table, schema version 4
- MySQL: `player_identities` table, schema version 6

Storage migrations include identities in the same exact snapshot as wallet balances, bank balances
and transaction history. Persisting an identity does not create a wallet or award a starting
balance.

Concurrent observations use last-login-wins name updates. A confirmed `BEDROCK` type cannot be
downgraded by another backend that lacks Floodgate information.

## Event updates

The service observes `PlayerJoinEvent` and `PlayerQuitEvent` at monitor priority. Join observations
update UUID, name, login time and platform type immediately in memory, then persist on the dedicated
identity executor. Name changes are naturally discovered at the next login.

## Optional Floodgate bridge

`plugin.yml` declares `softdepend: [floodgate]`. SodaEconomy does not compile against or require
Floodgate. When Floodgate is installed and enabled, a reflection-isolated bridge calls the official
`FloodgateApi#isFloodgatePlayer(UUID)` method and classifies the identity as `BEDROCK`. If Floodgate
is absent or its API cannot be initialized, SodaEconomy continues normally.

On proxy networks, Bedrock classification on a backend requires Floodgate's API to be available on
that backend and Floodgate data forwarding to be configured correctly. Without backend API data,
wallets remain fully UUID-safe, but the platform type may be `JAVA` or `UNKNOWN` until another
Floodgate-aware instance observes the UUID.

The stored last-known name is the Bukkit/Paper server-visible name, including any configured
Floodgate prefix. This avoids silently merging a Java and Bedrock player who share the same raw
username.

## Internal rules

- New code must use `PlayerIdentityApi`/`PlayerIdentityService` for player lookup.
- Do not call `Bukkit.getOfflinePlayer(String)` to resolve an economy account.
- Do not expose low-level identity storage methods to external plugins.
- Platform-specific logic belongs behind `PlayerPlatformBridge`; commands and economy services must
  remain platform-agnostic.
