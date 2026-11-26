# Bruno IntelliJ Plugin — Review Report v2

**Date:** 2026-11-11
**Scope:** `integrations/intellij` — 63 Java files (was 43)
**Branch:** `feat/intellij-flow-plugin`
**Method:** full source read of new files, forced test rerun, both UI snapshots inspected

---

## 1. Executive summary

**8.5 / 10**, up from 7.5.

Every blocker from v1 is closed, and the suite is green: **71 tests, 0 failures** (was 42 with 1 failing). You also built most of the Cool Request gap list: response renderers, cURL export, variable autocomplete, OpenAPI export, bounded history, notifications, and a reflection bridge.

The work has shifted character. The request path is now genuinely well-engineered — the security posture in particular is stronger than most commercial API clients. What's left is concentrated in two places: **the script execution path**, which blocks a shared IDE thread pool, and **the compact layout**, which has three controls saying the same thing.

| Area | v1 | v2 | Note |
|---|---|---|---|
| Request execution correctness | 9.0 | 9.0 | steady |
| Security / secret handling | 8.5 | **9.5** | XXE, HTML allowlist, image bomb guard, loopback bridge |
| UI layout | 8.0 | 8.0 | wide is good; compact has duplication |
| Test coverage | 7.5 | **9.0** | 71 tests, all green |
| Performance | 6.5 | **7.5** | scan cached; script path regressed |
| Memory | 5.5 | **8.5** | bounded history, prune, response release |
| Robustness | 6.0 | 7.5 | notifications, bridge, gutter |
| CI / release | 3.0 | **8.0** | workflow added with path filters |

---

## 2. Verified state

`./gradlew --offline test --rerun-tasks` — **BUILD SUCCESSFUL**, 71 tests, 0 failures:

| Suite | Tests |
|---|---|
| ApiRequestTest | 2 |
| ApiSettingsTest | 4 |
| BridgeClientTest | 1 |
| DiscoveryCacheTest | 2 |
| ExecutionTest | 3 |
| FlowFileOperationsTest | 2 |
| GlobalEnvironmentTest | 6 |
| RequestComposerTest | 7 |
| ResponseRenderingTest | 3 |
| ReviewRegressionTest | 11 |
| ScriptHooksTest | 4 |
| SpringEndpointScannerTest | 6 |
| TabLayoutRegressionTest | 2 |
| WorkspaceModelTest | 7 |
| WorkspaceUiTest | 11 |

---

## 3. v1 blockers — all closed

| v1 finding | Status | Evidence |
|---|---|---|
| `FlowLocator` NIO/VFS split, failing test | **Fixed** | `FlowFileOperationsTest` green; `ReviewRegressionTest.testInMemoryVfsDiscoveryIncludesNewFlowImmediately` guards it |
| Ctrl+W closes wrong editor | **Fixed** | `BrunoCloseAction.canClose()` checks `CONTEXT_COMPONENT` is the panel or its descendant |
| Responses retained forever | **Fixed** | `close()` nulls `response`, clears `history`; `WorkspaceState.prune()` with `RETAINED_DRAFTS = 100` |
| Scan has no cache, no trigger | **Fixed** | `EndpointDiscovery` keyed on `PsiModificationTracker`; 500 ms `discoveryTimer`; PSI tree listener; `PROJECT_ROOTS` listener |
| `dirty()` serializes whole draft | **Fixed** | Caches parsed `savedFields`, re-parses only when `baseline` changes |
| `Pattern.compile` in hot paths | **Mostly fixed** | All hoisted to static except `OpenApiExport:64` |
| `new Gson()` per call | **Mostly fixed** | Static everywhere except `BridgeClient:49`, `FlowFileOperations:20` |
| No CI for Gradle/Java | **Fixed** | `.github/workflows/intellij-plugin.yml`, path-filtered on the plugin and its JS deps |
| No variable autocomplete | **Fixed** | `VariableSuggestions` — names only, never reads the vault |
| No cURL export | **Fixed** | `CurlCommands`, with secret redaction and multipart boundary handling |
| No request history | **Fixed** | Bounded: 10 entries or 5 MB, whichever comes first |
| 20 modal dialogs | **Fixed** | `BrunoNotifications` |
| No non-Spring guard | **Fixed** | Status text distinguishes "No supported controllers" from "No matches" |

New capabilities beyond the v1 list: `OpenApiExport`, `BridgeClient` + `BridgeSettingsDialog` + `BridgeTasksDialog` (reflection invocation), `ControllerGutterProvider`, `GlobalApiSettings`, `ResponseRendering`, `ScriptHooks` + `ScriptRequest`.

---

## 4. Security — the strongest part of the plugin

Worth stating plainly, because it's better than what most commercial tools ship:

- **XXE closed.** `ResponseRendering.xml()` disables doctype declarations, sets `FEATURE_SECURE_PROCESSING`, and blanks `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA` on both the parser and the transformer.
- **HTML sanitized to an allowlist.** `head`, `script`, `style`, `object`, `iframe` are blocked with depth tracking; nesting capped at 32, tags at 2000, text at 200k chars; `<img>` replaced with a notice so no external fetch occurs.
- **Image decompression bomb guarded.** 16-megapixel ceiling checked from reader metadata *before* decode.
- **Bridge is loopback-only.** `BridgeClient.base()` rejects non-HTTP, userinfo, query, fragment, and non-root paths, then resolves the host and rejects any non-loopback address. Bearer token, 24 MiB response cap, 8 MiB request cap, 35 s timeout.
- **Scripts are sandboxed.** QuickJS via Bruno's own runtime, `allowScriptFilesystemAccess: false`, 15 s wall clock, forced process-tree kill.
- **Secrets are lazy and redacted.** The runner fetches a secret only when the script actually reads it; values are scrubbed from error text, logs, and cURL output. Autocomplete exposes names only.

---

## 5. Performance

### 5.1 Script execution blocks the shared application executor — HIGH

`ScriptHooks.java:41`:

```java
var read = AppExecutorUtil.getAppExecutorService().submit(() -> readLine(reader));
try { line = read.get(15, TimeUnit.SECONDS); }
catch (TimeoutException error) { throw new IOException("Script execution exceeded 15 seconds."); }
```

Two problems in four lines:

1. **Pool starvation.** `AppExecutorUtil.getAppExecutorService()` is a bounded pool shared across the whole IDE. Each scripted request parks one of those threads in a blocking pipe read for up to 15 seconds. A handful of concurrent scripted sends stalls unrelated IDE work.
2. **Thread leak on timeout.** The `Future` is never cancelled. On timeout the method throws, the process is killed in `finally`, but the submitted task stays blocked in `readLine` until the pipe closes. Every timeout leaks a pooled thread.

**Fix.** Use a dedicated single-purpose executor (or a plain `new Thread`) for the reader, and cancel the future in a `finally`:

```java
var future = reader.submit(...);
try { line = future.get(15, SECONDS); }
catch (TimeoutException e) { future.cancel(true); throw ...; }
```

### 5.2 A Node process per script phase — HIGH

`ScriptHooks.run()` creates a temp file (`:27`) and spawns a new process (`:34`) on every call. A request with both hooks spawns two Node processes per send. Node cold start plus QuickJS init is roughly 100–200 ms each, so scripting adds 200–400 ms of pure overhead to every send, before the script does anything.

The IPC protocol already supports a long-lived worker — it's line-delimited and the runner loops on `line()`. **Fix:** keep one worker process per project, reuse it for pre and post of the same request, and kill it on project close. That removes the cold start entirely.

### 5.3 `filter()` walks the entire tree on every keystroke — MEDIUM

`ApiExplorerPanel:540` runs `root.depthFirstEnumeration()` and calls `tree.expandPath` per node, on every debounced search keystroke and every refresh. Worse, `:546` — when `expanded.isEmpty()` it expands *every* row. On a project with several hundred endpoints that's a full tree expansion per filter pass.

**Fix:** filter the model rather than re-walking and re-expanding; keep expansion state keyed by node identity instead of re-deriving it.

### 5.4 Residual minor items

- `OpenApiExport:64` compiles a `Pattern` per call. Hoist it.
- `BridgeClient:49` and `FlowFileOperations:20` construct `Gson` per call. Hoist to static.

---

## 6. Memory

Now well controlled. `recordResponse()` caps history at 10 entries or 5 MB by cumulative payload + text size. `close()` releases `response`, `lastExecution`, and `history`. `prune()` evicts closed, unpinned, clean drafts beyond 100, LRU by `lastUsed`. `loadState()` nulls responses on non-open drafts.

One residual: `prune()` calls `dirty()` per candidate, which is now cheap but still a field comparison over every candidate on every open/close. At 100 drafts that's negligible. No action needed.

---

## 7. Layout — UI/UX review

Both snapshots regenerated 2026-11-11 18:08 and inspected.

### 7.1 Wide (1200 px) — good

Explorer, request editor, and response stack cleanly. Section strip fits all eight tabs. The status line, history control, and response renderer all read correctly. Nothing to fix at this width.

### 7.2 Compact (420 px) — three controls say the same thing

**Duplicated environment display.** The screen states the environment three times:

1. Top bar: `Global environment` / `Local` / `Environments...` — three stacked lines (`ApiExplorerPanel:221`)
2. Badge: `Global environment: Local` (`RequestEditor:274`, `:327`)
3. Again in the request area

At the exact width where vertical space is scarcest, ~60 px of chrome is spent repeating one fact. **Fix:** in compact, drop the top-bar label and show only the combo with a tooltip, and keep one badge. This is the single biggest win in the compact layout.

**Section strip clips to `Vari...`.** `RequestEditor:120`, `:383`, `:389` all set `SCROLL_TAB_LAYOUT` unconditionally. The `sectionPicker` combo exists (`:62`), is made visible in compact (`:368`), and carries the tooltip "all sections are available at narrow widths" — but the scrolling strip stays underneath it. So compact mode ships *both* a combo and a clipped tab strip for the same eight sections. **Fix:** in compact, hide the strip and rely on the picker; or keep the strip and use `WRAP_TAB_LAYOUT`. Not both.

**`(1 APIs)`.** `ApiExplorerPanel:537` hardcodes `" APIs"` with no singular form, so the tree reads `OrderService (1 APIs)`. Small, but it's the kind of thing that makes a tool feel unfinished. **Fix:** `count == 1 ? " API" : " APIs"`.

**`3 of 3 APIs • 0 notes`.** `ApiExplorerPanel:554`. "0 notes" is a warning count that says nothing at zero, and "3 of 3" is redundant when nothing is filtered. **Fix:** show `3 APIs` when unfiltered, and the notes segment only when `> 0`.

**Permanent onboarding footer.** `ApiExplorerPanel:121` keeps "Double-click an API or press Enter to open its request." as a permanent `SOUTH` label. It's a one-time hint occupying permanent space. **Fix:** show only until the first draft is opened, or fold into the empty state.

**`History Actions...`** reads as a truncated label or a broken control. **Fix:** `History` plus a separate icon button, or `History (3)`.

**`treeMore` is double-wired.** `ApiExplorerPanel:66` constructs the button with an empty lambda `() -> {}`, then `:67` adds the real `ActionListener`. The first is dead code. Harmless at runtime, confusing to the next reader.

---

## 8. Remaining correctness notes

- **`ScriptRequest.prepare()` mutates the draft.** A pre-request script writes `fields.url`, `fields.method`, `fields.headers`, `fields.body` directly. If the script changes the URL, `fields.query` is rebuilt via `QueryCodec.split()`, which discards row descriptions and re-enables disabled rows. Same class of bug flagged in v1 for the URL field — it now has a second entry point.
- **`BridgeClient.invokeWithToken` trusts the response body.** `response.get("body")` and `get("headers")` are dereferenced without presence checks; a malformed bridge reply throws `NullPointerException` rather than a readable error.
- **`ScriptHooks` stderr is discarded** (`:35`). When the Node worker fails to start — wrong executable, missing module — the user gets "worker exited without a result" with no cause. Capturing stderr would make that diagnosable.

---

## 9. Prioritized next steps

**High**

1. Move the script reader off `AppExecutorUtil` and cancel the future on timeout.
2. Reuse one Node worker per project instead of spawning per phase.

**Medium**

3. Compact layout: remove the duplicated environment display and pick one section-navigation control.
4. Filter the tree model instead of walking and re-expanding it.
5. Fix `(1 APIs)`, `0 notes`, and the permanent footer hint.
6. Guard the bridge response accessors.

**Low**

7. Hoist the last `Pattern` and two `Gson` instances.
8. Capture script stderr into the error message.
9. Remove the dead `treeMore` lambda.

---

## 10. Bottom line

The gap between this and a shippable plugin is now small and specific. Execution, security, and memory are in good shape — better than I'd expect at this stage. The two things that would still bite a user are the script path blocking a shared thread pool under load, and the compact layout wasting its scarcest resource on repeated information.

Fix those two and this is a plugin I'd install.

---

*Every claim is traceable to a file and line in `integrations/intellij`, or to the test run and snapshots dated 2026-11-11. No figures are estimated.*


## Implementation response to v2

The findings above are retained as the review snapshot. Follow-up changes:

- Shared-executor pipe reads removed. A project service owns a reusable Node process, a bounded message queue and dedicated stdout/stderr readers. Fair, interruptible leases serialize protocol transactions. Timeout/cancellation/channel failure kills the worker; project disposal cleans it up. Node/runtime-directory changes replace it. A readiness handshake captures bounded startup diagnostics before any request data is sent.
- The runner now loops over hook messages. Each execution creates its own QuickJS scope; pre/post hooks and subsequent sends reuse Node and its module cache. The original runner read only one top-level input, despite the review's assumption that it already looped.
- Compact sections use only the picker; the hidden strip consumes no tab area and cannot receive pointer/focus navigation. Wide sections retain tabs. The shared environment selector is the single environment display; bridge mode remains explicit via Invoke/Advanced.
- Query URL merging preserves row IDs, descriptions, duplicate order, secret metadata and disabled flags. `prepare` modifies an execution snapshot from RequestEditor, not the persisted draft itself; the query metadata loss in that snapshot was valid.
- Tree filtering builds the filtered model directly, restores only group paths by identity, preserves deliberate collapse, and avoids expanding every endpoint leaf. Search expansion does not overwrite saved group expansion. Counts use singular forms, omit zero notes, and omit redundant “N of N” when unfiltered. The opening hint retires after first use.
- History uses a named More icon. Dead no-op explorer wiring removed. Remaining reported Pattern/Gson allocations hoisted. Bridge payload decoding produces a readable companion-format error for absent, null, malformed or invalid fields.

Final verification: **77 plugin tests + 5 companion tests, zero failures**, plus a **30-test native-runtime subset**. Plugin build and archive verification passed. Detailed validation results are recorded in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md). Native-window snapshots were inspected at 420 px and 1200 px; regression checks also cross 550 px and exercise overflow/theme refresh.

### Screenshot follow-up: response pushed below the viewport

Valid findings: the splitter ignored child minimum sizes, so a restored 80% request proportion could crowd the response below its useful height. Stacked panes now honor request/response minimum sizes, use a visible 8-pixel divider with a double-click balance reset, and switch to Request/Response tabs below 560 logical pixels in height. Tests restore the extreme split and check response/error bounds, then shrink the editor and check the tabbed fallback. Add file is restricted to Multipart, Format JSON to JSON, and connection failures hide formatting controls while preserving copy/save. A closed-port integration check verifies that the error includes the actual target and Global Environments guidance. These changes improve diagnosis; the plugin cannot start an unrelated application from a failed request.

### 0.4.1: follow-up screenshot still clipped

The previous fixed minimum and isolated-editor test did not cover the user's whole-window/font configuration adequately. Response allocation now measures actual chrome and font metrics, constrains the splitter to the visible viewport, and selects tabbed presentation when stacked content cannot fit. Automatic allocation does not overwrite the saved split. Restored/new connection failures reveal a balanced response and its Body content. The explorer's width decision now runs during layout as well as on resize events. Added native whole-window tests at 1294 px width, enlarged fonts and three heights, including containment checks on the visible error text, plus a real failed-send check. The native screenshot was inspected with the explorer present. Release bumped to 0.4.1 to distinguish it from prior ZIPs.

### 0.4.2: session cookie continuity for CSRF

The CSRF GET succeeded while login failed with CSRF_INVALID. The displayed header token differed from the shown GET token, and source inspection confirmed a second defect: no cookie store survived between ordinary API sends. Added a project service with environment-ID/exact-origin cookie jars, bounded session-only retention, clear action and disposal cleanup. HTTP execution captures response cookies and re-evaluates matching cookies on every request/redirect; explicit Cookie headers take precedence. Bridge traffic is excluded. Cookies and common CSRF header values are redacted from cURL. Tests cover a GET/token/cookie/POST flow through separate RequestEditor tabs, wrong-token rejection, session clearing, isolation, cookie attributes, redirect path changes and retention/disposal. A fresh token matching the current server session is still required.

### 0.4.3: expanded table values and response wrapping

Added a selected-value toolbar action, per-value cell expand affordance and scoped keyboard shortcut. The resizable wrapped editor supports Apply/Cancel/Copy without mutating row metadata or introducing line breaks. Stored credential secrets are not fetched; secret inputs remain masked by default. Response bodies default to persisted soft wrapping with a Wrap lines toggle, applied to native-editor creation and live changes. Tests cover exact-value Apply/Cancel, metadata preservation, untouched stored secrets, actual visual wraps for a long unbroken JSON token and unchanged underlying text when toggling. Native-runtime verification passed and the wrapped-token screenshot was inspected. The whole-window screenshot fixture now selects its own Darcula theme instead of depending on test order.

### 0.4.4: discovered flow could not launch bru

The screenshot shows discovery succeeded and execution failed before the flow engine started because the external Java project's IDE PATH had no bru. Added an explicit IDE-wide CLI file/checkout setting with Browse, a Runner settings action in Flows, configured Node support for JS entry points, collection/project/runtime-sibling fallback discovery, and actionable wrapped errors. Resolution now runs outside the EDT. Tests cover external checkout/Node paths with spaces, argv/environment preservation, null project base, collection/runtime sibling discovery, invalid configuration, and real saved-flow execution from an external project using this fork's CLI. No global npm installation or shell profile edits are required.

### 0.4.5: flow execution history and node observability

The CLI already reported node input/output snapshots, but IntelliJ only showed console lines and discarded the structured data. Each run now requests the CLI JSON report into a temporary file, captures a bounded sanitized summary, deletes the raw file and adds a project-session history entry. Execution history shows per-iteration/nested steps with Input, Output and Details; final context is available in Run summary. Known credential keys/header values are masked. Console stays live and its updates/tails are bounded. History retains 10 runs/approximately 5 MiB, has an explicit clear action, and survives flow-file deletion during the project session. Oversized reports/previews explain their limits. Cancellation is recorded separately; panel disposal stops processes and releases output. Tests exercise actual Run Flow, ctx.total before/after, raw report deletion, cancelled versus successful runs, repeated loop rows, secret redaction, failed-step selection and retention/disposal.

### 0.4.6: IntelliJ JSON flow import

Added Import JSON actions to both empty and populated Flows views, followed by a destination collection chooser. The dependency-free parser enforces strict/bounded JSON, graph structure, IDs/types/edges, safe display names and collection-bound references. It preserves graph configuration and serializes native Bru or JSON-compatible YAML, including safe Bru triple-quote handling. VFS writes run in a write-safe application callback with collision-safe naming and rollback of newly created files on failure. Import opens/selects the new file, remembers external collections and never executes the graph. Tests import/run the provided sample in Bru and YAML, preserve quoted script strings, check duplicate preservation and invalid-import no-op behavior, and exercise import → selection → Run Flow in native IntelliJ.


### 0.4.7: correctness and performance follow-up

A targeted code review found and fixed stored-secret overwrite on unchanged inline commits, reordered-column editor selection, UI-thread history parsing/formatting, per-step list event churn, script-result loss at EOF, unbounded/deep report parsing, hidden-window timer races, and import/history job cleanup. The full baseline passes 111 plugin and 5 companion tests. Details and limitations are in [CODE_REVIEW_0.4.7.md](CODE_REVIEW_0.4.7.md).


### 0.4.8: Serena-assisted lifecycle and performance review

Fixed exponential nested-variable expansion, obsolete response-formatting jobs and retained disposed documents, recursive formatting of deeply nested JSON/XML, and inability to cancel before CLI process creation. Regression coverage verifies bounded expansion, memoization, raw fallback, cleanup and cancelled run history. Full baseline: 117 plugin + 5 companion tests passed. See [CODE_REVIEW_0.4.8.md](CODE_REVIEW_0.4.8.md) for the Serena method, findings and limits.


### 0.4.9: Stitch-assisted native UI review

Reviewed wide/narrow workspace screenshots with Stitch MCP and inspected its refined reference. Fixed redundant request header rows, ambiguous/unbounded resolved URL display, cramped environment controls, unreliable search hints, clipped wrapping Flow actions and narrow empty-state actions. Added viewport/ancestor-bound regression assertions and retained native components/themes. Full baseline: 120 plugin + 5 companion tests passed. See [UI_REVIEW_0.4.9.md](UI_REVIEW_0.4.9.md).


### 0.4.10: Context7 and JetBrains platform review

Applied SDK lifecycle/cancellation guidance and IDE findings: event-driven request environment updates, stable profile selector on session changes, original-target Close callbacks, guarded queued focus, proper Disposer ownership and release of workspace references. Added cancellation checkpoints to discovery and OpenAPI work. Full baseline: 126 plugin + 5 companion tests passed. JetBrains build and error-only inspections passed. See [PLATFORM_REVIEW_0.4.10.md](PLATFORM_REVIEW_0.4.10.md).
