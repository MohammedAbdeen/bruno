# Serena-assisted IntelliJ plugin review — 0.4.8

Reviewed 2026-11-21. Scope: request variable resolution, response formatting/lifecycle, and flow-runner cancellation, with regression checks for HTTP execution, JSON flow import and step history.

## Review method

Used Serena MCP to activate the plugin project, search the relevant execution/lifecycle paths, and apply targeted source edits. Java symbol queries returned empty results in this environment; inspection therefore used Serena pattern searches and direct source reads rather than claiming successful semantic reference analysis. Gradle tests provide the behavioral verification. No unrelated code was refactored.

## Fixed findings

| Priority | Issue | Fix and regression evidence |
| --- | --- | --- |
| High | Nested variables could expand exponentially on the preview path. A 40-level doubling chain stayed below the existing recursion limit while requesting enormous allocations. | Memoize resolved variables for each expansion and check the output limit before appending. Expanded fields are limited to 1,048,576 Java characters. Tests reject a 40-level doubling chain, verify repeated references reuse resolutions, preserve literal special characters, and preserve large literal bodies that contain no variables. |
| Medium | Superseded response-formatting jobs continued running after renderer/response changes and tab closure. Disposed views also retained response documents and picker contents. | Track and cancel rendering futures, retain generation checks for late callbacks, ignore updates after disposal, and clear response documents/history. Tests cover cancellation on replacement and disposal, document release, and refusal to populate a disposed view. |
| High | Arbitrarily deep JSON and XML could reach recursive pretty printers, risking stack exhaustion and large formatting work. | JSON uses the strict depth-limited parser before formatting. XML checks element depth before transformation, with interruption checks. Failed previews preserve raw response text. Tests exercise 1,000-level JSON/XML input and verify safe fallback/rejection. |
| Medium | Stop was disabled while resolving the CLI, and stopFlow returned without recording cancellation when no process existed. A launch could follow cancellation during setup. | Enable Stop from the beginning, record cancellation independently of process existence, and recheck before launch and after process registration. Test cancels before process creation, verifies no launch, records cancelled history, and restores the busy state. |

## Verification

- Full IntelliJ 2024.3 baseline: **117 plugin tests passed**, zero failures/errors/skips.
- Spring bridge: **5 tests passed**, zero failures/errors/skips.
- Distribution build and structure verification succeeded.
- Native IntelliJ **2026.3 (263.7307.200)** with bundled JBR: **32 focused checks passed**, zero failures/errors/skips, including native UI, HTTP execution, flow import/run and the new regressions. Its stricter XML parser rejects depth earlier; the test accepts either parser rejection or the plugin guard, while requiring a depth-related error.
- Version 0.4.8 archive integrity, descriptor version, changed classes and companion setup version were verified.
- Source whitespace/conflict checks and git diff --check passed.

## Limits and remaining observations

This is a targeted review, not a proof that every plugin or monorepo issue has been eliminated. Cancellation prevents queued work and rejects stale UI updates; a library already decoding an image or formatting XML may not immediately honor interruption. Existing response preview/image bounds remain in effect.

The plugin structure task continues to warn about the existing plugin ID containing “intellij”; the ID is retained for update compatibility. The Gradle plugin reports that 2.20.1 is available; upgrading build tooling is outside these correctness fixes.
