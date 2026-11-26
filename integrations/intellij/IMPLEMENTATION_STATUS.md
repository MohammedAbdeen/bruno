# IntelliJ implementation status

Active release: 0.4.10. Delivery branch: feat/intellij-plugin-ui-review.

Implemented:
- IDE-wide environment/profile storage and migration from existing project profiles, preserving secret references and name collisions.
- One global API environment selector; synchronized request tabs and project windows.
- Controller + endpoint method gutter icons; method/route chooser and matching request draft reuse.
- Wrapped transport errors, meaningful variable empty state, compact response actions and visible splitters.
- Automatic image/HTML/XML/JSON/text response rendering; passive bounded HTML, secure XML, bounded image decoding, original-image save.
- Source-derived OpenAPI export with DTO/record schemas and path/query/header/body metadata.
- Last-dispatch-input duplication.
- Bruno QuickJS pre/post hooks via cancellable Node worker, lazy credential RPC, profile-scoped session variables and optional IDE runtime configuration.
- Opt-in authenticated loopback Spring bridge, direct MVC adapter invocation, annotated scheduled/XXL tasks and native configuration/task UI.
- CI Node/runtime preparation and modern Spring bridge verification.

Review v2 follow-up implemented:
- Project-scoped reusable Node worker; fair interruptible hook leases, fresh scopes, dedicated bounded pipe readers, startup diagnostics, disposal and cancellation/recovery.
- Compact section dropdown without a duplicated tab strip; one global environment display.
- Script URL changes preserve query metadata, duplicate order and disabled rows.
- Group-only expansion restoration, concise counts, retired onboarding hint, named history menu, malformed bridge response handling and minor allocation/wiring cleanup.

Validation completed:
- Full baseline suite after review v2: 87 plugin tests and 5 companion tests; zero failures/errors/skips.
- Latest native subset: 30 tests passed, including worker reuse/isolation, cancellation/recovery, queued cancellation, project disposal, query metadata and bridge reply validation. Native-window navigation, overflow, theme-refresh, responsive-width, global-environment and workspace checks passed against IntelliJ 2026.2.3 (IU-262.10968.63) and its bundled runtime.
- A resize regression found during native-window verification was fixed: RequestEditor evaluates its actual width during layout, before queued resize events.
- The 5 companion integration tests also passed on Boot 4.1.1 / Spring 7.0.9 using baseline-compiled classes and real XXL-Job 2.4.1; baseline is Boot 3.4.0 / Spring 6.2.0.
- buildPlugin and verifyPluginStructure completed successfully. Structure verification reports the existing plugin-ID naming warning; the ID is retained for upgrade compatibility.
- ZIP integrity and embedded global settings, gutter provider, script worker, companion JAR and Maven POM verified.
- README, UX_PLAN, REVIEW_REPORT and companion setup documentation updated.

Handoff:
- Plugin: build/distributions/bruno-intellij-plugin-0.4.6.zip.
- Normal HTTP works without Node or the companion. Scripts require Node and a built Bruno JS package; direct invocation/tasks require the opt-in application companion.
- User's installed plugin was not replaced, and no commit or push was performed.

Screenshot follow-up verified:
- Stacked request/response minimum sizes, visible draggable divider with double-click reset, and short-window tab fallback.
- JSON/Multipart body actions match their mode; connection failures hide formatting controls and retain copy/save.
- Closed-port execution reports the actual target and application/Global Environments guidance.
- Native IntelliJ 2026.2.3 layout/error checks passed; response-error snapshot inspected. Full suite: 80 plugin + 5 companion tests, zero failures. buildPlugin/structure task and ZIP integrity passed.

0.4.2 follow-up verification:
- Whole-tool-window native tests use a visible JFrame, explorer, enlarged fonts, multiple heights, and visible error-text containment checks.
- Allocation derives from actual response chrome/font metrics and the visible viewport. Failed sends reveal the error response; automatic sizing preserves saved split preferences. Explorer reflow runs during layout.
- Full release suite: 82 plugin + 5 companion tests, zero failures/errors/skips. 0.4.2 ZIP integrity, plugin descriptor version and embedded companion POM version verified.

0.4.2 CSRF/session support:
- Normal API tabs share bounded, transient cookies by project/environment ID/exact origin. Cookie Secure/path/expiry rules and explicit-header precedence are preserved; redirects re-evaluate paths. More provides session clearing.
- Cookies and common CSRF headers are masked in cURL; Details identifies automatic cookies without values. Bridge invocation is excluded.
- Integration tests cover actual CSRF and login RequestEditor tabs, matching-token success, wrong-token rejection, profile/origin isolation, clearing, redirect-path changes, retention bounds and disposal.
- Full release suite: 87 plugin + 5 companion tests; zero failures/errors/skips. Version 0.4.2 ZIP integrity and packaged cookie service verified.

0.4.3 value readability:
- Value cells have an expand icon; selection enables Expand value with a scoped Ctrl/Cmd+Shift+Enter shortcut. A resizable wrapped dialog supports Apply/Cancel/Copy, preserves metadata and never auto-loads stored secrets.
- Response Wrap lines defaults on, persists in IDE settings and applies to native-editor creation/live toggles. Wrapping does not modify copied/saved characters.
- Native tests verified actual visual wraps for an unbroken token, exact text preservation, expanded Apply/Cancel and unchanged stored-secret references. Wrapped screenshot inspected.
- Complete release suite: 90 plugin + 5 companion tests, zero failures/errors/skips. Build, structure task, source whitespace and ZIP integrity/version checks passed.

0.4.4 external-project flow execution:
- Browsable IDE-wide CLI file/checkout setting and Flows Runner settings button. JavaScript entry points honor the configured Node; collection/project/runtime sibling discovery falls back to PATH. Resolution runs outside the EDT and long output/errors wrap.
- Complete suite and package build passed. Nineteen native-runtime checks passed, including real saved-flow execution from an external Java project with PATH empty and an explicit Node path.
- 0.4.4 ZIP integrity and plugin version verified. CI prepares CLI dependencies alongside QuickJS and tracks relevant source changes.

0.4.5 execution history:
- Flows now captures the CLI JSON reporter into a temporary file and shows project-session run history with per-step Input, Output and Details; repeated/nested invocations are preserved and failures selected automatically. Console stays live and bounded.
- Captured known credential fields are masked. Temporary raw reports are removed after capture. History retains up to 10 runs/approximately 5 MiB; report/preview limits are explicit. Closing the project or clearing history releases snapshots.
- Real Run Flow tests verify ctx.total input=0/output=60, temporary-report deletion and separate successful/cancelled records. Unit/UI coverage checks retention, redaction, repeated steps and selection.
- Full suite: 98 plugin + 5 companion tests, zero failures/errors/skips. 0.4.5 build, structure task and archive integrity/version/history classes verified. Final native history/CLI subset and screenshots checked.

0.4.6 JSON import:
- Import JSON is available in empty and populated Flows. Choose source/destination; worker validation/serialization precedes a write-safe VFS creation command. Imported files are opened/selected and never auto-executed.
- Accepts wrapped v1 exports and raw graphs; validates strict JSON, size/depth, types/IDs/edges/coordinates/names/references. Preserves configuration/mappings, removes runtime UI state, and handles duplicate names without overwriting. Native Bru and JSON-compatible YAML output are covered by real CLI execution, including quoted script values.
- Invalid imports leave no files/metadata; existing files are preserved. Referenced resource files are not bundled.
- Complete release suite: 105 plugin + 5 companion tests, zero failures/errors/skips. Native importer/overflow subset passed (9 tests), screenshot inspected, archive/importer/version verified.


0.4.7 correctness/performance review:
- Fixed unchanged stored-secret commits, column-reordering secret editors, sorted row removal and per-paint renderer hierarchy churn.
- Large flow reports load/render outside the EDT, with one list batch, bounded formatting, cancellation and stale-result protection. Strict report parsing handles malformed fields/depth, bounded reads close the size-check race, encoded JSON bodies are redacted, and retention bytes are cached.
- Script EOF preserves completed messages; chunked pipe reads replace per-character framing. Closed workers cannot be reused.
- Automatic discovery rechecks visibility at timer execution and defers hidden construction. Pending imports/history jobs are cancelled on disposal; completion cleanup restores Run controls reliably.
- Full baseline: 111 plugin + 5 companion tests passed, zero failures/errors/skips. See CODE_REVIEW_0.4.7.md for scope, evidence and limits.
- Native IntelliJ 2026.2.3 focused verification: 31 tests passed with native UI windows. Build/structure and 0.4.7 ZIP integrity/version/classes verified; history snapshot inspected. The installed plugin was not modified.


0.4.8 Serena-assisted review:
- Used Serena MCP searches and targeted edits to review variable expansion, response lifecycle/formatting, and pre-launch flow cancellation.
- Memoized variable resolution, bounded expanded fields before allocation, and preserved large literal bodies.
- Cancelled obsolete render jobs and released response documents/history on disposal; guarded deeply nested JSON/XML formatting with raw fallback.
- Stop works during CLI resolution and prevents subsequent launch; cancellation is retained in run history.
- Full baseline: 117 plugin + 5 companion tests, zero failures/errors/skips. See CODE_REVIEW_0.4.8.md for evidence and limits.
- Native IntelliJ 2026.3 (263.7307.200): 32 focused checks passed. Updated XML-depth assertion for its stricter parser; source behavior is safe on both runtimes. 0.4.8 ZIP integrity/version/classes verified. Installed plugin unchanged.


0.4.9 Stitch-assisted UI review:
- Used Stitch MCP to create a private review project and refine a layout from existing sample screenshots. Official CLI handled screenshot upload/download.
- Unified request commands, labeled bounded resolved URL previews, compacted the global environment row, and added native search hints/accessibility names.
- Added width-aware Flow action wrapping, stacked collection environment controls, and narrow empty-state layout. Resizing preserves component attachment and draft contents.
- Full baseline: 120 plugin + 5 companion tests passed, zero failures/errors/skips. See UI_REVIEW_0.4.9.md for design reference, findings and verification.
- Final native IntelliJ 2026.3: 31 UI/integration checks passed. Screenshots inspected; final resolved-preview alignment verified. 0.4.9 ZIP integrity/descriptor/layout classes and companion version checked (340,797 bytes). Installed plugin unchanged.


0.4.10 Context7/JetBrains platform review:
- Used Context7 SDK guidance and JetBrains inspections/call analysis/build against the imported plugin project. Initial IDE type-resolution errors cleared after synchronization/build; no toolbar rewrite was required.
- Removed repeating per-request settings polling in favor of lifecycle-bound events; session-only changes no longer rebuild the environment selector.
- Cached Close actions retain their original request target. Queued focus ignores inactive/disposed editors; workspace references and data providers are released on disposal.
- Flow history is a Disposer child; discovery/route expansion/OpenAPI work has cooperative cancellation checkpoints. Added six behavioral regressions.
- Full baseline: 126 plugin + 5 companion tests passed, zero failures/errors/skips. JetBrains build succeeded and seven inspected implementation files reported zero errors. See PLATFORM_REVIEW_0.4.10.md.
- Final native IntelliJ 2026.3: 48 UI/integration checks passed. 0.4.10 ZIP integrity/version/implementation classes verified (341,334 bytes). Installed plugin unchanged; changes are local.
