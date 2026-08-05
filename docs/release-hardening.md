
## Optional Vault provider

When Vault is present, SodaEconomy registers one `Economy` provider through Bukkit's
`ServicesManager`. The adapter never accesses `Storage` directly: all deposits and withdrawals use
the ordered `TransactionService`, exact minor units, the immutable journal, statistics, audit and
rollback paths. Local YAML/SQLite operations drain older write-behind work and commit directly to
the atomic backend before Vault receives `SUCCESS`; MySQL retains its existing ACID and row-lock
authority.

The Vault dependency is startup-only and optional (`provided`/`compileOnly`, `softdepend: Vault`).
A safely cancellable queue timeout returns `FAILURE` before mutation starts. Once authoritative
execution has started, SodaEconomy waits for its definitive result rather than returning an
ambiguous failure which could later become a committed ghost transaction. See
`docs/vault-integration.md` for configuration, identity resolution, bank semantics and the release
smoke-test checklist.
