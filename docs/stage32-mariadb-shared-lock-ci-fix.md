# Stage 32 CI Fix: MariaDB Shared-Lock Syntax

## Problem

After Stage 31, the MySQL 8.4 CI matrix leg passed. The MariaDB 11.8 LTS
matrix leg still failed during `mysqlIntegrationTest` with many
`SQLSyntaxErrorException` failures during `MySQLStorage.init(...)`.

Because the MySQL leg passed, the remaining issue was isolated to SQL accepted
by MySQL 8.4 but rejected by MariaDB 11.8 during schema initialization.

## Root cause

Stage 31 removed the dialect-sensitive `LIMIT ? FOR UPDATE` migration query.
However, the mutation-gate check still used this query shape:

```sql
SELECT maintenance_active, owner_token
FROM sodaeconomy_runtime_state
WHERE state_name=?
FOR SHARE
```

That check runs inside `inTransaction(...)` during startup because the schema
bootstrap creates legacy opening-balance journal entries through the normal
transaction path. Therefore a dialect error there causes almost every MariaDB
integration test to fail at initialization.

## Fix

Stage 32 changes the shared mutation-gate read to:

```sql
SELECT maintenance_active, owner_token
FROM sodaeconomy_runtime_state
WHERE state_name=?
LOCK IN SHARE MODE
```

This preserves shared-lock semantics for ordinary wallet mutations while
avoiding the MariaDB 11.8 syntax failure.

## Compatibility

- No schema version changed.
- No table or column changed.
- No runtime behavior changed beyond SQL dialect compatibility.
- The mutation gate remains shared-read locked for normal transactions.
- The exclusive maintenance path still uses `FOR UPDATE`.
- MySQL 8.4 remains supported.
- MariaDB 11.8 remains supported.
