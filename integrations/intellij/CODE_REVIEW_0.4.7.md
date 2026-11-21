# IntelliJ plugin code review — 0.4.7

Reviewed 2026-11-18. Scope: IntelliJ plugin request editing, script worker, automatic discovery, JSON import, flow execution and history. This is a targeted review, not an audit of the entire Bruno monorepo.

## Findings and fixes

| Priority | Finding | Correction |
| --- | --- | --- |
| High | Committing an unchanged stored-secret cell marked its blank placeholder as edited. Saving the environment could replace the credential. Reordered columns could also choose the wrong cell editor. | Ignore unchanged edits and resolve view/model indices before selecting or initializing secret editors. Regression exercises focus/commit, reordered Description/Value columns, and a real replacement. |
| High | Large flow reports were parsed, flattened and pretty-printed on the UI thread. Updating the list once per step caused repeated UI work. | Reports of at least 32 KiB load and render in cancellable background tasks. Publish one list batch; reject stale callbacks. Pretty-print into a bounded writer instead of allocating the full formatted output before truncating. |
| High | Null or malformed report fields could throw during completion and skip restoration of Run controls. Deep reports used unrestricted parsing. | Reuse strict, interruptible, depth-limited JSON parsing; validate report shapes; handle invalid status values. History failures cannot bypass completion cleanup. Run remains busy until its completion callback executes. |
| Medium | The script pipe reader cleared queued messages on EOF, potentially discarding a completed result if Node exited immediately afterward. | Preserve delivered messages and queue terminal errors after them. A process-exit regression reproduces this ordering. Closing workers are excluded from reuse. |
| Medium | Script pipe framing read one character at a time for messages allowed up to 24 MiB. | Read 8 KiB chunks while preserving newline framing and enforcing message and queue limits. Startup failure also terminates descendants. |
| Medium | Visibility was checked before scheduling discovery, but not when the delayed timer fired. Construction also started discovery while hidden. Test mode bypassed the visibility condition. | Check visibility on the UI thread and again at timer execution; defer initial discovery and stop pending timers on hide. Tests inject visibility rather than bypassing production behavior. |
| Medium | A report could grow after its file-size check. History repeatedly encoded every retained report while holding its monitor. | Bound the actual read to 2 MiB plus one detection byte. Normalize outside the history lock and compute each run's UTF-8 retention size once. |
| Medium | Credentials inside a JSON-encoded response-body string escaped field-based redaction. | Parse JSON-shaped body/data strings with the bounded parser and redact nested credential fields while preserving their string representation. |
| Medium | Pending import and history jobs survived panel disposal; early import setup errors could leave controls busy. | Cancel pending jobs on disposal and restore controls on setup/submission failures. Flow rescans respect import state. |
| Low | Value rendering rebuilt its component hierarchy for each cell paint; row deletion used view indices. Credential checks recompiled a regex per variable. | Reuse renderer children, translate sorted row indices, and precompile the credential pattern. |

## Verification

- Full IntelliJ 2024.3 baseline: **111 plugin tests passed**, zero failures/errors/skips.
- Spring companion: **5 tests passed**, zero failures/errors/skips (unchanged companion sources).
- Large-history regression: 1,000 steps published in one list insertion; a 200,000-character captured value remains intact in history while the visible preview is limited to 128 KiB of characters.
- Focused coverage includes real CLI execution/cancellation, report-file cleanup, import into Bru/YAML and execution, malformed/deep reports, encoded-body redaction, saved-secret editing, reusable script workers and EOF behavior, and hidden-window discovery.
- Native IntelliJ 2026.2.3 / bundled JBR: **31 focused tests passed**, zero failures/errors/skips, with native UI windows. Flow-history and token-wrapping snapshots were generated; the history snapshot was inspected.
- Version 0.4.7 ZIP integrity, descriptor, reviewed classes and companion version message were verified (333,307 bytes).
- Build and plugin structure task succeeded. The structure task continues to warn about the existing plugin ID containing “intellij”; the ID is retained for update compatibility.

## Practical limits

History remains session-local, with 10 runs and approximately 5 MiB retained. Individual report files are limited to 2 MiB; displayed step previews to 128 KiB of characters. Known credential keys are masked; arbitrary free-form console output is not guaranteed secret-free. This review does not establish a universal latency bound for very large Java projects or replace profiling against the user's running application.
