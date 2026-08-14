# Contributing to SodaEconomy

Thank you for considering a contribution to SodaEconomy. This project prioritizes transaction
integrity, predictable migrations, backward compatibility, and maintainable integrations over
shortcuts or feature speed.

## Before you start

- Search existing issues and discussions before opening a new report.
- Use the provided issue forms for bugs, features, and support questions.
- Discuss large architectural changes before implementing them.
- Never publish passwords, database credentials, access tokens, private keys, or unredacted server
  configuration.

## Supported development environment

- Java 17 bytecode target
- Paper API 1.20.2 as the current compile baseline
- Gradle Wrapper from the repository for canonical release verification; Maven Wrapper for parity verification
- MySQL 8.x for the optional integration test suite

SodaEconomy currently targets modern Paper/Purpur servers. See `docs/compatibility.md` before
changing the API baseline or Java release target.

## Build and test

Gradle is the canonical release build. A pull request must pass the Gradle verification path:

Windows PowerShell:

```powershell
.\gradlew.bat clean test
.\gradlew.bat mysqlIntegrationTest jacocoTestReport jacocoTestCoverageVerification shadowJar
```

Linux/macOS:

```bash
./gradlew clean test
./gradlew mysqlIntegrationTest jacocoTestReport jacocoTestCoverageVerification shadowJar
```

Maven remains supported as a secondary parity build and must also remain green:

```bash
./mvnw -B -ntp clean verify
```

CI verifies both build definitions. Only the Gradle `shadowJar` output is the canonical release
artifact. See `docs/build-contracts.md`.

### MySQL integration tests

Use a dedicated test database. Never point the suite at a production database.

```powershell
$env:SODAECONOMY_TEST_MYSQL = "true"
$env:SODAECONOMY_TEST_MYSQL_HOST = "127.0.0.1"
$env:SODAECONOMY_TEST_MYSQL_PORT = "3306"
$env:SODAECONOMY_TEST_MYSQL_DATABASE = "sodaeconomy_test"
$env:SODAECONOMY_TEST_MYSQL_USER = "soda"
$env:SODAECONOMY_TEST_MYSQL_PASSWORD = "replace-with-local-test-password"

.\gradlew.bat mysqlIntegrationTest
```

Run `./mvnw -B -ntp clean verify` separately for Maven build parity.

More details are available in `src/test/README.md`.

## Architecture requirements

Contributions must preserve these project invariants:

1. **All balance mutations go through `TransactionService` or the supported public API.**
   Commands, integrations, and external plugins must not mutate storage directly.
2. **Success is never reported before the authoritative operation is committed.**
   Do not introduce fire-and-forget transaction paths or ghost transactions.
3. **Money is represented internally in exact minor units.**
   Convert external decimal values through `Money`; never perform internal balance arithmetic with
   `double`.
4. **Journal, audit, statistics, rollback, and transaction events stay consistent.**
   New mutation paths must not bypass any of them.
5. **Storage compatibility is mandatory.**
   YAML, SQLite, and MySQL must remain supported. Schema changes require safe, tested migrations
   that preserve existing data.
6. **Bukkit/Paper objects stay on the server thread.**
   Async completion handlers must schedule Bukkit work back to the main thread.
7. **Player lookups use `PlayerIdentityService` / `PlayerIdentityApi`.**
   Do not add new direct name-based `OfflinePlayer` lookup logic.
8. **Optional integrations remain optional.**
   Use `compileOnly`/`provided`, `softdepend`, and an isolated adapter. The plugin must start normally
   when the integration is absent.
9. **Runtime reload remains explicit and atomic.**
   Only documented safe settings may reload through `/eco reload`; startup-only settings must not
   change partially.
10. **User-facing messages belong in the language system.**
    Do not hard-code command or administrator messages in production code.

## Coding guidelines

- Use Java 17-compatible language and library features.
- Prefer immutable value objects and explicit result types.
- Keep public APIs small, documented, and backward compatible.
- Validate nullability and invalid external input at API boundaries.
- Avoid blocking the Paper main thread. If a synchronous third-party API forces blocking, use a
  bounded timeout and preserve an unambiguous commit result.
- Use the existing debug/logging system rather than ad-hoc console output.
- Add focused regression tests for every bug fix.
- Do not weaken safety checks merely to make a test pass.

## Pull request process

1. Create a focused branch, for example `feature/update-checker` or `fix/mysql-deadlock-retry`.
2. Keep commits understandable and scoped to one concern.
3. Update documentation and language files when behavior changes.
4. Run the full verification suite.
5. Complete the pull request template and link the relevant issue.
6. Address review feedback without force-pushing over active review unless necessary.

Large unrelated refactors should not be bundled with a feature or bug fix.

## Commit messages

Use concise imperative messages, for example:

```text
Add optional GitHub update checker
Fix persisted failure outcome comparison
Document Vault timeout semantics
```

## Documentation changes

Update the appropriate files when changing:

- compatibility: `docs/compatibility.md`
- runtime reload: `docs/config-reload.md`
- player identities: `docs/player-identities.md`
- Vault behavior: `docs/vault-integration.md`
- public usage or installation: `README.md`
- release-visible behavior: `CHANGELOG.md`

## Contribution licensing and rights

By intentionally submitting a contribution for inclusion in SodaEconomy, you
agree that the contribution is provided under the same project license:
Apache License 2.0 subject to the Commons Clause License Condition v1.0 and the
express permissions stated in `LICENSE`.

You retain copyright in your contribution. You confirm that:

- you created the contribution or otherwise have the necessary rights to
  submit it;
- the contribution does not knowingly include material under incompatible
  terms;
- required third-party attribution is disclosed; and
- you are authorized to submit the work on behalf of an employer or other
  rights holder when such authorization is necessary.

SodaEconomy does not currently require a separate Contributor License
Agreement. A maintainer may request provenance or rights information when a
contribution contains third-party material or raises a licensing concern.

## Responsible contributions

Only submit work you are legally permitted to contribute. Do not copy code, assets, or documentation
from incompatible projects. Security vulnerabilities must follow `.github/SECURITY.md` and must not
be disclosed in a public issue before a fix is available.
