# Stage 31 CI Fix: MySQL Migration SQL and BankManager Coverage

## Problems addressed

Stage 30 fixed the Gradle VaultAPI classpath problems. The next CI run exposed
two unrelated issues:

1. In one database matrix leg, the shared JDBC integration suite failed during
   schema initialization with `SQLSyntaxErrorException`.
2. In the other database matrix leg, integration tests passed but JaCoCo failed
   because `de.sodaeconomy.BankManager` line coverage was `0.79` while the
   configured minimum is `0.80`.

## MySQL/MariaDB SQL fix

The legacy exact-balance migration previously used a prepared statement shaped
like:

```sql
SELECT ... FROM balances WHERE uuid>? ORDER BY uuid ASC LIMIT ? FOR UPDATE
```

This query is part of schema migration and is already protected by the
SodaEconomy named schema lock. Stage 31 removes the dialect-sensitive
parameterized `LIMIT ... FOR UPDATE` combination and uses the internal constant
batch size directly in the SQL text:

```sql
SELECT ... FROM balances WHERE uuid>? ORDER BY uuid ASC LIMIT <internal batch size>
```

The value is not user-controlled. This keeps the migration deterministic and
avoids a prepared-statement syntax edge case across the supported MySQL 8.4 and
MariaDB 11.8 matrix.

## Coverage fix

Stage 31 does not lower the JaCoCo threshold. Instead, it adds real unit
coverage for previously under-covered `BankManager` facade paths:

- asynchronous wallet-to-bank deposits;
- asynchronous bank-to-wallet withdrawals;
- asynchronous administrative bank-balance updates;
- asynchronous bank-balance reads;
- all-bank-balance reads;
- compatibility `adminSetBankBalance`;
- interval-aware interest application.

## Compatibility

No schema version, table layout, plugin configuration, PlaceholderAPI behavior,
Vault behavior, or public command behavior changed.
