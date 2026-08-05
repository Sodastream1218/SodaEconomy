# Security Policy

SodaEconomy handles balances, transaction history, database credentials, and cross-plugin economy
operations. Security and data-integrity reports are therefore treated separately from ordinary bugs.

## Supported versions

Until the first stable release, security fixes are normally developed for the latest public
prerelease and the current `main` branch.

| Version | Security support |
| --- | --- |
| Current `main` branch | Active development |
| Latest published alpha/beta/RC | Supported |
| Older prereleases | Best effort; upgrade may be required |
| Modified forks or unofficial builds | Not supported by the SodaEconomy maintainer |

After `1.0.0`, this table will be updated with a formal supported-release window.

## Reporting a vulnerability

Do **not** open a public issue for an unresolved vulnerability.

Use GitHub's private vulnerability reporting for this repository:

1. Open the repository's **Security** tab.
2. Select **Advisories**.
3. Choose **Report a vulnerability**.

If private vulnerability reporting is not available, contact the maintainer through a private method
listed on [@Sodastream1218's GitHub profile](https://github.com/Sodastream1218). Do not include exploit
details in a public discussion, issue, or pull request.

## What to include

Provide as much of the following as possible:

- affected SodaEconomy version or commit;
- Paper/Purpur, Java, Vault, Floodgate, and storage versions;
- storage backend and whether multiple servers share MySQL;
- clear reproduction steps or a minimal proof of concept;
- expected and observed balance/journal behavior;
- whether the issue can duplicate, delete, expose, or corrupt data;
- relevant sanitized logs and configuration;
- suggested mitigation, if known.

Remove passwords, tokens, database URLs containing credentials, private keys, and personal data.

## Security-sensitive examples

Examples include:

- balance duplication or unauthorized withdrawals;
- permission bypasses for administrative economy operations;
- journal, rollback, idempotency, or audit bypasses;
- SQL injection or unsafe migration behavior;
- credential or private configuration disclosure;
- remote code execution or unsafe deserialization;
- race conditions that allow overspending across server instances;
- spoofed player identity resulting in access to another account.

Ordinary configuration questions, unsupported-version problems, and non-sensitive bugs should use the
normal issue forms or Discussions.

## Disclosure process

The maintainer will assess the report, request additional information when needed, prepare a fix and
migration guidance, and coordinate public disclosure after a safe release is available. Exact response
or release times cannot be guaranteed, especially during prerelease development.
