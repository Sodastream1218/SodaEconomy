# Licensing and Release Documentation Audit

## Scope reviewed

The Stage 26 repository was reviewed before implementation, including:

- project and repository structure;
- Maven and Gradle build definitions and wrappers;
- README and documentation;
- existing contribution and third-party notices;
- GitHub workflows, issue forms, pull-request template, security and support
  files;
- `plugin.yml` runtime libraries and optional dependencies;
- direct production, runtime-loaded, provided, test, build, and CI
  dependencies;
- existing copyright, license, trademark, and source-header patterns; and
- release-JAR resource handling.

## Findings before Stage 27

- No top-level `LICENSE` existed.
- No `LICENSE-FAQ.md`, `TRADEMARKS.md`, or top-level `NOTICE` existed.
- `CONTRIBUTING.md` existed but did not explicitly state the contribution
  licensing rule or confirm that no separate CLA was required.
- `THIRD_PARTY_NOTICES.md` covered the main runtime components but did not
  inventory test, build, wrapper, and CI dependencies.
- A third-party notice was already embedded in `META-INF`, but no project
  license or notice was embedded in the release JAR.
- Java source files consistently began with package declarations and did not
  use project source-license headers. No mass source-header insertion was
  necessary.
- The repository contained no conflicting project copyright statement.

## Stage 27 changes

Stage 27 adds or updates only legal, attribution, contribution, release, and
repository-governance documentation:

- `LICENSE`
- `NOTICE`
- `LICENSE-FAQ.md`
- `TRADEMARKS.md`
- `THIRD_PARTY_NOTICES.md`
- `CONTRIBUTING.md`
- `README.md`
- `CHANGELOG.md`
- `.github/CODEOWNERS`
- `.github/SUPPORT.md`
- `.github/pull_request_template.md`
- `docs/dependency-license-audit.md`
- embedded `META-INF` legal resources
- bundled third-party license copies

No Java source, YAML configuration, public API, database schema, dependency
version, Maven configuration, Gradle configuration, or CI workflow logic was
changed.

## Consistency validation

The final documentation consistently states that:

1. SodaEconomy is source available under Apache License 2.0 subject to Commons
   Clause v1.0 and the express project permission in `LICENSE`.
2. Monetized Minecraft servers and networks are expressly permitted.
3. Server revenue from ranks, shops, crates, donations, advertising,
   subscriptions, and access is permitted.
4. General Minecraft hosting may include SodaEconomy as one component without
   a separate SodaEconomy fee.
5. Selling the JAR, charging for a download, rebranding a premium clone, or
   commercially offering a substantially equivalent substitute is prohibited
   without written permission.
6. Free forks remain possible subject to license notices, change marking, and
   the separate trademark policy.
7. Modified builds must use another primary name and must not appear official.
8. Contributions are submitted under the same project license, contributors
   confirm necessary rights, and no separate CLA is currently required.
9. Third-party components retain their own licenses and are not relicensed by
   SodaEconomy.

## Build-impact validation

The licensing stage intentionally leaves all build definitions and production
code byte-for-byte unchanged. Legal resources are added through the existing
resource-copy and shading behavior, so no build process or plugin behavior is
altered.

A full dependency-resolving Maven/Gradle build requires network access or a
populated local dependency cache. The supplied environment did not provide the
wrapper distributions or dependency cache, so the final build should be
confirmed by the existing GitHub CI matrix after push. The Stage 27 validation
therefore verifies structural integrity, unchanged build/code files, document
consistency, and release-archive contents.
