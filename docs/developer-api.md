# Developer API

SodaEconomy v1 exposes two supported integration services through Bukkit's `ServicesManager`:

- `EconomyTransactionApi` — wallet mutations, exact read snapshots, history, rollback and statistics
- `PlayerIdentityApi` — read-only UUID/name resolution

Concrete managers, storage classes and `TransactionService` are runtime implementation details and
must not be used as a new plugin's integration boundary.

## Obtain the economy API

```java
RegisteredServiceProvider<EconomyTransactionApi> registration = Bukkit.getServicesManager()
        .getRegistration(EconomyTransactionApi.class);
if (registration == null) {
    return;
}

EconomyTransactionApi economy = registration.getProvider();
```

The service is registered after SodaEconomy's storage/transaction initialization succeeds.

## Prefer exact money methods

New integrations should use `BigDecimal` or canonical minor units:

```java
economy.deposit(
        playerId,
        new BigDecimal("25.00"),
        TransactionOrigin.api("ExamplePlugin"),
        TransactionRequestOptions.of("Quest reward", Map.of("quest", "starter"))
);
```

or:

```java
economy.depositMinor(playerId, 2500L, origin, options);
```

The historical `double` mutation methods remain supported for v1 compatibility. SodaEconomy's exact
built-in path keeps minor-unit values as `long` instead of round-tripping them through binary floating
point.

## Idempotent external operations

For an operation that your plugin may retry, use a stable idempotency key representing **one logical
payment**:

```java
TransactionRequestOptions options = TransactionRequestOptions.idempotent(
        "Quest reward",
        Map.of("quest", "starter"),
        "starter-quest:" + playerId
);
```

Do not reuse one key for unrelated transactions.

## Asynchronous contract

The public economy API is asynchronous. Futures complete with SodaEconomy's authoritative result;
persistent read failures complete exceptionally rather than returning fake zero/empty data.

Completion callbacks may run away from the Paper main thread. Do not access Bukkit `Player`, world,
inventory or other thread-confined server objects from those callbacks without scheduling back to the
correct Paper thread.

## Transaction results

Mutations return `TransactionResult`. Check `result.isSuccessful()` and use
`TransactionFailureReason` for machine-readable failures. Successful wallet mutations participate in
the immutable journal and normal audit/statistics/event model.

Rollback creates a new reversing record; it never edits/deletes the original transaction.

## Read operations

- `getStoredBalance(UUID)` reads without creating an account or awarding starting balance.
- `getStoredBalancesMinorUnits()` returns an exact wallet snapshot.
- `getStoredBankBalancesMinorUnits()` returns an exact bank snapshot.
- `findTransactions(TransactionQuery)` queries the immutable ledger asynchronously.
- `getStatistics()`, `getAnalytics()` and `getPlayerStatistics(UUID)` expose aggregate views.

A legitimately missing wallet may be represented as zero. A backend read failure is exceptional and
must not be interpreted as real economy data.

## Identity API

```java
RegisteredServiceProvider<PlayerIdentityApi> registration = Bukkit.getServicesManager()
        .getRegistration(PlayerIdentityApi.class);
```

`findOnlinePlayer(...)` and `suggestKnownNames(...)` are server-thread operations. Persistent
`resolve(...)` and `resolveDisplayNames(...)` operations are asynchronous. Unknown or ambiguous names
do not fabricate accounts.

## Public API boundary and deprecations

Legacy low-level getters/managers retained for compatibility are deprecated since `1.0` and marked for
future removal. New integrations must not use them. Storage access is guarded at runtime specifically
to prevent external code from bypassing transaction/journal invariants.

The intended v1 compatibility contract is the two public service interfaces and the public value types
required by them. Breaking changes to that supported surface should be reserved for an appropriate
major-version boundary.

## Nullability

The public service contracts reject required null inputs where applicable and use `Optional` for
identity absence. API consumers should treat required UUIDs, origins, options and queries as non-null
unless a method's JavaDoc explicitly states otherwise.

## Example: transfer

```java
TransactionOrigin origin = TransactionOrigin.api("ExamplePlugin");
TransactionRequestOptions options = TransactionRequestOptions.idempotent(
        "Marketplace purchase",
        Map.of("listing", listingId),
        "market:" + listingId
);

economy.transfer(buyerId, sellerId, new BigDecimal("49.99"), origin, options)
        .thenAccept(result -> {
            if (!result.isSuccessful()) {
                // Handle result.failureReason().
                return;
            }
            // Schedule any Bukkit/Paper work back to the server thread.
        });
```
