# Security Policy

SodaEconomy handles balances, transaction history, database credentials and cross-plugin economy
operations. Security and data-integrity reports are therefore treated separately from ordinary bugs.

## Supported versions

The first stable release line is intended to receive security fixes on the latest available `1.0.x`
release. Development builds and prereleases may receive fixes while being prepared, but production
server owners should upgrade to the latest supported stable patch once `1.0.0` is published.

| Version | Security support |
| --- | --- |
| Latest `1.0.x` stable release | Supported |
| Current `main` branch | Active development; not a production support promise |
| Older alpha/beta/RC builds | Best effort; upgrade may be required |
| Modified forks / unofficial builds | Not supported by the SodaEconomy maintainer |

The supported-release window may be expanded for later major/minor lines and will be documented here.

## Reporting a vulnerability

**Do not open a public issue for an unresolved vulnerability.**

Use GitHub private vulnerability reporting for this repository:

1. Open the repository's **Security** tab.
2. Open **Advisories**.
3. Choose **Report a vulnerability**.

If private vulnerability reporting is unavailable, contact the maintainer through a private method
listed on [@Sodastream1218's GitHub profile](https://github.com/Sodastream1218). Do not include exploit
details in a public discussion, issue or pull request.

## What to include

Provide, when relevant:

- affected SodaEconomy version or commit;
- Paper/Purpur and Java versions;
- storage backend/database version and whether multiple servers share it;
- Vault, PlaceholderAPI and Floodgate versions when involved;
- clear reproduction steps or a minimal proof of concept;
- expected and observed balance/journal behaviour;
- whether the issue can duplicate, delete, expose or corrupt data;
- relevant sanitized logs/configuration;
- suggested mitigation, if known.

Remove passwords, tokens, database URLs containing credentials, private keys and personal data.

## Security-sensitive examples

Examples include balance duplication or unauthorized withdrawals, permission bypasses, journal or
rollback bypasses, SQL injection, credential disclosure, remote code execution, unsafe migration
behaviour, race conditions that allow overspending, or identity spoofing that affects another account.

Ordinary configuration questions and non-sensitive bugs should use the normal support/issue channels.

## Disclosure process

The maintainer will assess the report, request additional information when needed, prepare a fix and
migration guidance, and coordinate public disclosure after a safe release is available. Exact response
or release times cannot be guaranteed.
