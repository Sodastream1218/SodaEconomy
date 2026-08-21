# Stage 33 CI Fix: MariaDB Cross-Instance Concurrency

## Problem

After Stage 32, the MariaDB 11.8 matrix no longer failed during schema
initialization. Only two high-contention concurrency tests remained failing:

- `preventsOverspendingAcrossTwoIndependentStorageInstances`
- `serializesParallelVaultStyleWithdrawalsAcrossTwoTransactionServices`

The MySQL 8.4 matrix passed. The remaining failures were therefore isolated to
MariaDB/InnoDB behavior under concurrent wallet mutations through independent
storage and transaction-service instances.

## Root cause

Both failing tests intentionally issue many concurrent debits/transfers against
the same wallet rows through two independent JDBC connections. MariaDB can abort
some of those transactions with transient InnoDB deadlock or lock-wait failures
even when the application uses stable row-lock order.

Stage 32 already fixed SQL syntax compatibility. Stage 33 hardens the retry and
isolation policy so transient storage-engine concurrency failures are retried
long enough to resolve to the correct domain result:

- successful debit/transfer while funds remain;
- `INSUFFICIENT_FUNDS` after the balance is exhausted.

## Fix

Stage 33 changes the shared MySQL/MariaDB JDBC storage path as follows:

1. Sets new JDBC connections to `Connection.TRANSACTION_READ_COMMITTED`.
2. Increases the transaction retry budget from 4 to 12 attempts.
3. Replaces the linear retry sleep with capped exponential backoff plus jitter.

The wallet-row serialization remains based on explicit `SELECT ... FOR UPDATE`
locks. The maintenance gate and schema coordination remain unchanged.

## Compatibility

- No schema version changed.
- No DDL/table/column changed.
- No build workflow changed.
- No plugin metadata changed.
- No JaCoCo threshold changed.
- MySQL 8.4 remains supported.
- MariaDB 11.8 remains supported.
- The change improves production resilience for multi-server/network setups.
