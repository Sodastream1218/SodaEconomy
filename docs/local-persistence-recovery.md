# Local Persistence Recovery

SodaEconomy's YAML and SQLite write-behind mode uses a small local recovery write-ahead log (WAL)
as a durability bridge for mutations that have been accepted in memory but have not yet been
confirmed by the authoritative local backend.

This file is **not** a second transaction-history database. Long-term wallet history, audit data,
rollback records and statistics continue to live in the configured authoritative storage backend.

## Durability contract

For a queued local mutation, the order is:

```text
validate mutation
→ update protected runtime state
→ append compact pending recovery record
→ force the WAL record to disk
→ mutation may be reported as locally accepted
→ ordered async backend persistence
→ backend commit confirmed
→ recovery record becomes obsolete
```

The public asynchronous `EconomyTransactionApi` continues waiting for authoritative persistence
before its future completes. Existing synchronous gameplay/compatibility paths may return after the
local acceptance barrier, but that accepted state is already represented by a forced recovery WAL
record.

If the recovery append itself fails, SodaEconomy rolls back the unprotected in-memory change and
does not report the mutation as successfully accepted.

## What one recovery record contains

A WAL entry contains only the minimum state required to reconcile one unresolved queue write:

- an internal unique recovery write ID;
- enqueue timestamp;
- absolute post-write wallet balances for touched accounts;
- absolute post-write bank balances for touched accounts;
- the immutable wallet transaction record when the write has one;
- account-initialization metadata when a newly accepted account has not reached the backend yet.

Amounts use canonical `long` minor units. The recovery format does not serialize money through
`double` and does not use native Java object serialization.

The WAL deliberately does **not** copy all older transaction history on every mutation.

## Healthy steady state

Recovery growth is tied to unresolved work rather than server lifetime:

```text
authoritative backend caught up
+ no pending queue entries
= no local recovery WAL payload
```

When the queue drains completely, the WAL is removed. During continuous backlogs, committed WAL
records are periodically compacted away with an atomic replacement when the filesystem supports it.
If atomic replacement is unavailable or cleanup fails, SodaEconomy retains the older WAL instead of
risking recovery-data loss.

The existing bounded async queue remains the primary backlog limit. In addition, the recovery WAL
has an internal 256 MiB safety ceiling; a mutation that would exceed it is rejected before it can
remain accepted without recoverable state. Once the queue reaches its configured capacity, recovery
cleanup cannot be repaired, or persistence is paused, new economy mutations are rejected/failed
before an unprotected runtime balance change can escape.

## Crash behavior

### Crash before the WAL record is durable

The mutation is not safely accepted. A recovery-write failure is propagated and the tentative
runtime change is rolled back.

### Crash after the WAL is durable but before backend persistence

Startup reads and validates the WAL before the async queue becomes active, then reconciles its
absolute post-write state and immutable transaction record into the authoritative YAML/SQLite
backend.

### Crash during backend persistence

The local backend's normal atomic transaction/file-replacement guarantees decide whether the
backend commit occurred. The WAL remains available. Startup reconciliation is safe in either case.

### Backend committed, crash before WAL cleanup

The stale WAL entry may still be present. Recovery does not re-run the player-level operation; it
reconciles absolute state and stable immutable transaction IDs. An already committed transaction ID
is therefore not appended a second time.

### Crash during WAL compaction

The last known-good WAL remains authoritative until an atomically written compacted file replaces
it. SodaEconomy does not fall back to a non-atomic replacement when atomic move is unavailable.

## Direct durable operations

Some compatibility/integration paths deliberately drain the async queue and write directly to the
local authoritative backend. Before such a direct write is allowed, SodaEconomy verifies that no
stale committed WAL residue remains. This prevents an older recovery record from being reconciled
over a newer direct backend mutation after a later crash.

## Corruption handling

The WAL has:

- magic/header identification;
- explicit format version;
- framed record lengths;
- CRC32 per record;
- bounded collection/string/record sizes;
- duplicate recovery-write-ID rejection.

Truncated, checksum-invalid, unsupported or structurally invalid recovery state causes startup
recovery to fail closed. The original recovery evidence is preserved rather than silently deleted.
If an append fails while the process is still alive, SodaEconomy attempts to truncate/delete the
partial append back to the last valid boundary. If that rollback cannot be guaranteed, subsequent
mutation admission is blocked instead of appending acknowledged state behind an uncertain WAL tail.

## Legacy recovery files

The previous `local-persistence-recovery.yml` format (version 1) remains readable for upgrades.
SodaEconomy restores that snapshot first and removes it only after the authoritative local backend
accepts the restored exact snapshot. New runtime recovery uses `local-persistence-recovery.wal`.

If both the legacy and current recovery files are present simultaneously, SodaEconomy refuses to
guess which one is authoritative.

## Backups

Recovery is not a backup system. It protects acknowledged in-flight local persistence work.
Administrators should still maintain backups for disk loss, operator mistakes, corrupt databases
and migrations/upgrades.

## Stress verification

Normal test runs exclude the intentionally expensive filesystem stress category. Run it explicitly:

```bash
./gradlew localPersistenceStressTest
./gradlew localPersistenceStressTest -Dsodaeconomy.recovery.stress.mutations=50000
./gradlew localPersistenceStressTest -Dsodaeconomy.recovery.stress.mutations=100000
```

Maven equivalent:

```bash
./mvnw -Precovery-stress test -Dsodaeconomy.recovery.stress.mutations=10000
```

The test reports WAL peak bytes, bytes per pending mutation, append throughput and cleanup time. It
uses structural size assertions rather than fragile hard real-time performance thresholds.
