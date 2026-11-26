# IntelliJ plugin: usability and capability plan

Status: core request workspace implemented for 0.3.0 on `feat/intellij-flow-plugin`. The sections below remain the full target specification; this release does not complete every phase.

### Implementation checkpoint — 0.3.0

- Phase 1 core: module/controller tree, intersecting search/module filters, independent persistent drafts, dirty tab titles, duplicate/pin/reopen actions, source navigation, and the 550 logical-pixel responsive breakpoint.
- Phase 2 core: environment manager and credential-store secrets; structured parameter/header/path/variable editors and bulk round trips; authentication and JSON/text/form/multipart bodies; resolved URL preview; Send shortcut, cancellation and deadlines; Body/Headers/Details response views; project and IDE-default transport settings.
- Verified by comprehensive plugin tests, including real local HTTP exchanges, deadline/cancellation behavior, draft persistence, duplicate keys, secret references, query parameter merge preservation, and wide/narrow UI layout checks. Test-rendered images are layout aids, not validation of the installed plugin in IntelliJ 2026.2.
- Still pending in Phases 1–2: variable autocomplete, comprehensive missing-variable highlighting across editors, further onboarding/accessibility polish, and installed-IDE visual verification.
- Phases 3–5 remain planned: richer/incremental discovery and its settings, collection persistence, bounded history/search/diff, and visual flow editing with desktop fallback. The existing CLI Flows tab remains available.

The following baseline comparison describes the previous 0.2.0 UI. Interaction requirements incorporate the reviews of navigation, History, Configuration, and icons.

This plan is based on the supplied Cool Request screenshots and the current 0.2.0 plugin. The immediate objective is a comfortable daily workflow: find a controller endpoint, configure a request, send it, understand its response, and return to it without losing work.

## Current gaps and design direction

| Area | Current plugin | Proposed experience |
| --- | --- | --- |
| API discovery | Flat table of methods, paths, and controller names | Project → module → controller → endpoint tree, with counts and method badges |
| Navigation | Discovery, configuration, and responses stacked together | Explorer and request workspace; explorer can collapse in a narrow tool window |
| Request state | One shared URL/body/response editor | Independent request tabs with saved drafts and clear dirty indicators |
| Headers and variables | Plain multiline text fields | Enabled/key/value/description tables, with optional bulk text editing |
| Environment selection | Profile controls consume request-editor space | Compact selector; a dedicated environment manager |
| Response | Status, headers, and body in one text area | Status summary and separate formatted body, headers, and details views |
| Discovery errors | Notes tab and generic status text | Endpoint-level explanations and actionable empty states |
| Saved work | Environment profiles persist; request drafts do not | Restore drafts, tabs, selection, environment, and splitter sizes |
| Bruno integration | CLI flow runner is separate from direct HTTP requests | Save discovered requests to collections, then reuse them in flows |

The reference's module/controller hierarchy, focused request tabs, parameter tables, and response summary are useful patterns. Its first screenshot also includes framework JAR endpoints and unresolved property placeholders. Our default view should emphasize application controllers, with dependency endpoints available through an explicit toggle. Unresolved paths must be explained before sending.

## Intended workspace

```text
Bruno
┌─────────────────────────┬──────────────────────────────────────────────┐
│ APIs / Collections      │ GET /users/{id}   POST /users   [+]          │
│ Search…   Module: All   │ Environment: Local ▾     Save    More…       │
│                         ├──────────────────────────────────────────────┤
│ TradingEngine           │ GET ▾  {{baseUrl}}/users/{{id}}   Send / Stop│
│  UserService (9)        ├──────────────────────────────────────────────┤
│   UserController       │ Params | Path | Headers | Auth | Body        │
│    GET /users/{id}      │ ✓  Key             Value          Description│
│    POST /users          │ ✓  Authorization   Bearer {{token}}          │
│   AuthController       │ [+] [-]                              Bulk Edit│
│                         ├──────────────────────────────────────────────┤
│ Flows                   │ 200 OK       142 ms       1.2 KB             │
│ History                 │ Body | Headers | Details                     │
│ Environments            │ Pretty / Raw     Search    Copy    Save      │
└─────────────────────────┴──────────────────────────────────────────────┘
```

This is a layout sketch, not a requirement to keep every section visible. The responsive breakpoint is **550 logical pixels of tool-window content width**, measured after IDE display scaling. Below 550 px, show either the explorer or the request workspace. Selecting an endpoint opens its request workspace with **Back to APIs**. At 550 px and above, show a resizable explorer beside the request workspace. Request and response areas remain vertically resizable in both modes.

Back to APIs changes navigation only: retain every draft, response, selected environment, dirty flag, and running request. Restore the explorer's filter, expansion, selection, and scroll position; return focus to the selected endpoint. Resizing across the breakpoint preserves the active tab and execution state. Returning to wide mode restores the previous splitter ratio. Closing a request is a separate action with the lifecycle rules below.

Use IntelliJ theme colors, fonts, actions, icons, and editor components. Icons need tooltips and accessible names. HTTP method badges must include text as well as color. Keep Send prominent and move infrequent actions into a menu.

### Shared interaction rules

These rules apply across phases; implement each rule when its owning control is introduced.

| Control | Required behavior |
| --- | --- |
| Send | Use the theme's primary/accent button style. Register **Send Bruno Request**, default Ctrl+Enter on Windows/Linux and Cmd+Enter on macOS, scoped to the active Bruno request workspace and configurable in the IDE keymap. Show the active shortcut in the tooltip. |
| Sending state | Show an indeterminate spinner and “Sending…” beside the action; replace Send with Stop. Show a small spinner on the running tab. Disable the Send action for that tab until completion; repeated shortcuts must not start duplicate requests or unexpectedly cancel one. Other tabs remain usable. |
| Request snapshot | Capture URL, parameters, headers, body, and resolved environment when Send is pressed. Edits during execution affect the next send. The response Details tab always describes the captured request. |
| Resolved URL | Place a read-only preview directly below the URL field, using secondary grey italic text with theme-appropriate contrast. Update on URL/environment/variable changes without moving focus. Wrap to two lines, then offer an expand action. Mask secrets in the preview. |
| Missing variables | Highlight unresolved variable tokens amber in editable fields and the URL preview, with an accessible warning icon and tooltip such as “Variable userId is not defined in Local.” Show missing names beside header/body editors too. Disable Send while enabled request fields contain unresolved or cyclic references. Disabled rows do not block sending. |
| Dirty tab | Prefix the title with **•**, for example “• GET /users/{id}”. Set it when request fields or the tab's environment selection differ from the last explicitly saved baseline. Sending and background draft recovery do not clear it. Save clears it; Revert restores the baseline. Responses, focus changes, and tab navigation do not make a request dirty. |
| Saving before collections | In Phases 1–2, Save means **Save Draft** in local project state and its tooltip states that scope. When collection saving arrives, use **Save to Collection…** as a separate action until a draft is bound to a saved request. |
| Closing a tab | Close cancels that tab's active execution and retains its recoverable local draft, including dirty state. Provide Reopen Closed Request. Discard Draft is a separate explicit action; do not discard edits as a side effect of navigation or closing. |

For method badges, use the target IDE's JetBrains HTTP client palette as the visual reference and select colors for both light and dark themes. The semantic fallback palette is GET green, POST amber, PUT blue, PATCH purple, DELETE red, HEAD teal, OPTIONS grey, and TRACE/ANY neutral grey. Match the installed HTTP client's styling during visual review where it differs; do not assume there is a stable public palette API or copy fixed RGB values from one theme. Every badge retains its method text and accessible contrast.

### Keyboard and focus behavior

- Tab order in the request toolbar: request tabs → environment selector → environment manager → Save/More → method → URL → Send/Stop → request section tabs → active editor → response section tabs → active response viewer. Skip disabled/hidden controls; Shift+Tab reverses the order.
- In the explorer, Up/Down selects rows, Left collapses or selects the parent, Right expands, and Enter opens the selected endpoint. In a request tab strip, Left/Right selects tabs and Enter activates the selected tab.
- Parameter tables use arrow keys for cell navigation, Enter/F2 to edit, Tab/Shift+Tab to commit and move between cells, and Escape to cancel an uncommitted cell edit. Multiline body editors retain their normal indentation behavior and provide an explicit keyboard action to leave the editor; do not trap keyboard focus.
- Register **Close Bruno Request**. Support Ctrl+W on Windows/Linux and Cmd+W on macOS while the request tab strip has focus, subject to keymap conflicts. Inside an editor, preserve its existing keymap actions (Ctrl+W can mean Extend Selection in IntelliJ); expose the configurable close action through Find Action and the tab context menu. Do not install an application-wide key listener.
- After opening a request, focus its URL field. After closing, focus the adjacent tab or the explorer if no tabs remain. After Send/Stop, retain focus on the invoking control and announce status changes without stealing focus.
- Before registering defaults, inspect the active keymap and surface conflicts in the keymap configuration rather than silently replacing another IDE action.

## Phase 1 — Navigation and independent request workspaces

Deliver the new layout first, using the existing scanner and HTTP execution capabilities.

- Group endpoints by project, module, and controller. Display method badges and endpoint counts; preserve expansion and selection when refreshing.
- Add search by path, method, controller, or handler, plus module filtering and expand/collapse actions. Apply module filtering first, then case-insensitive search within that scope (AND). Split search into whitespace-separated tokens; every token must match at least one searchable endpoint field. Keep matching endpoints' ancestor nodes visible. Show “12 of 54 APIs” for matches versus the selected scope's total. Changing module preserves search text; clearing search preserves module. A no-match state offers separate Clear Search and All Modules actions.
- Open endpoints in request tabs. Reuse an existing tab for the same endpoint unless the user chooses Duplicate Request. The discovery identity is module origin + source-file identity + fully qualified controller + handler name and parameter-type signature; an endpoint's edited URL is never its identity. For inherited handlers, include the concrete controller identity. Multiple mapping variants under one handler get distinct child identities based on their source mapping declaration/variant, including routing conditions. Every user draft has its own UUID; Duplicate always creates a new draft UUID.
- Give every tab its own method, URL, headers, parameters, body, environment selection, execution state, and response. Switching endpoints must not carry another endpoint's body into a request.
- Support close, close others, duplicate, pin, source navigation, and opening a blank request. Preserve unsaved drafts across restart.
- Separate request drafts from discovered metadata. A controller rename or route change should offer to update the draft without overwriting edits.
- Replace repeated log messages with states for indexing, scanning, no controllers, scan failures, and no search matches. Offer the relevant next action in each state.

Source-file moves and handler renames should preserve identity through IDE refactoring information when available. When that correspondence cannot be established, keep the old draft as detached and offer Relink to Endpoint; do not overwrite it or reuse it solely because another endpoint has the same URL.

Until Phase 4, hide History and Collections navigation entries. If a roadmap preview is deliberately displayed, show these entries disabled with “Available in a later release” tooltips and accessible descriptions. No inactive item should look clickable. Keep the existing functional Flows view available.

### First-run experience

- Open with a compact welcome state: **Discover Project APIs**, **New Request**, and **Open Bruno Collection** (the last action becomes available when collection support ships). Explain that controller discovery requires a supported imported Java Spring project; a manual request does not.
- During indexing show “Waiting for project indexing…” while keeping New Request usable. After scanning, display the endpoint count, select the first populated module, and offer a short dismissible hint pointing to environment selection and Send. Do not send requests automatically.
- Before the first discovered API request, show the editable Local base URL and explain that the application must be running. Keep the existing localhost value visibly editable rather than implying it was detected from the application.
- If there are no supported controllers, show Import Maven/Gradle Project guidance, Refresh APIs, New Request, and Supported Frameworks. Do not show an empty picker or repeated console errors.
- Remember dismissal of welcome hints per project. An upgrade from 0.2.0 preserves environments and reports migration results once; it must not run the first-run setup again over existing values.

Acceptance: open two endpoints, edit both, switch repeatedly, refresh discovery, and restart the IDE without mixing or losing their state. Verify dirty markers, close/reopen, keyboard focus, and first-run/upgrade states. Check widths of 420, 549, 550, 551, and 1200 logical pixels in light and dark themes, including resizing during a request and Back to APIs without losing its draft.

## Phase 2 — Complete the daily request workflow

Implement Phase 2 in this order: **2A environments and variable resolution → 2B structured request editors → 2C responses and execution polish**. The Phase 1 workspace uses existing sending capabilities while these changes are delivered.

### 2A — Environments and variable resolution

- Keep a compact environment selector in the request toolbar; move profile creation, duplication, renaming, deletion, and editing into a dedicated manager.
- Separate environment default headers from per-request overrides. Define header precedence as environment defaults → request overrides → explicitly configured authentication. Show inherited and overridden values.
- Define variable precedence as project defaults → selected environment → request-local values. Reserve the Base URL field for `baseUrl` and show where each resolved value came from.
- Store values marked secret through the IDE credential store. Keep ordinary values and references in project-local settings; exclude secret values from shared exports and history.
- Migrate existing 0.2.0 profiles without losing headers or variables. Offer migration of existing sensitive values into secret storage.

Secret controls show a fixed **••••** mask, independent of value length, plus an eye button with Show/Hide Secret accessible names. Reveal only the selected field; hide it again after 15 seconds, when it loses focus, or when its tab/environment closes or changes. A dedicated Copy Secret action copies the raw value and displays “Secret copied” without the value. Ordinary preview/copy/export actions remain redacted; avoid copying the mask as if it were a real credential. Bulk editing must preserve secret references and must not expose resolved secret values. Never write secrets into notification text, diagnostics, or history metadata.

Acceptance gate for 2A: switching profiles updates the grey resolved-URL preview; missing variables show amber warnings and prevent sending; secret reveal/copy behave as specified; existing profiles survive migration. Parameter tables depend on this gate.

### 2B — Structured request editors

- Add tables for query parameters, path variables, and headers, with enabled toggles, add/remove rows, keyboard editing, and bulk edit.
- Preserve duplicate query keys and empty values. Treat header names case-insensitively when applying overrides. Encode path and query values separately rather than encoding the whole URL.
- Keep the URL and parameter table synchronized without double encoding, using the shared resolution-preview rules above.
- Provide body modes: None, JSON, Text, form URL encoded, and multipart. Use syntax highlighting and JSON formatting/validation; keep a raw editing option.
- Provide Auth modes: None, Bearer, Basic, and API key. Show which headers or query parameters authentication will supply and resolve conflicts visibly.
- Offer variable completion for URLs, headers, parameters, and bodies. Preview secret values masked by default.

#### Table ↔ Bulk Edit contract

- Use one ordered row model shared by both views. Preserve row IDs, order, enabled state, empty values, descriptions, and secret references. An unchanged table → bulk → table switch is lossless and does not mark a draft dirty.
- Bulk mode uses a documented escaped line format: `key=value`; split on the first unescaped equals sign. Escape backslash, newline, carriage return, tab, and equals with `\\`, `\n`, `\r`, `\t`, and `\=` respectively. A leading `# ` disables a row; escape a literal leading `#` as `\#`. Optional descriptions follow an unescaped tab and use the same escapes. Ignore blank lines, but represent an empty value explicitly as `key=`.
- An Apply action parses and validates the entire bulk buffer before replacing rows. Invalid lines receive line numbers and inline errors; the existing table stays intact. Cancel restores the pre-edit buffer. Leaving bulk mode with unapplied edits offers Apply, Discard Bulk Changes, or Stay.
- Duplicate query keys and repeated headers remain separate ordered rows; never collapse them into a map or silently join values. Header override matching is case-insensitive across scopes: request-level rows replace the inherited group of the same name, preserving repetitions within the chosen scope. Reject conflicting duplicates for singleton headers such as Authorization, Host, and Content-Length; explain the conflicting rows.
- Path-variable and environment-variable names must be unique. Report duplicates before applying. Placeholder text and secret references round-trip literally; toggling editor modes never resolves or sends values.

### 2C — Responses and execution

- Show status, elapsed time, and response size in a persistent summary bar. Distinguish HTTP error responses from connection, TLS, timeout, and cancellation failures.
- Separate response Body, Headers, and Details. Add formatted JSON with folding, raw text, search, copy, and download. Respect content type and charset; identify binary responses.
- Display truncation explicitly and allow saving large responses without loading the entire body into the editor.
- Support Send through a configurable IDE action, cancellation, and visible progress. Closing a tab or project must clean up its execution and streams.
- Make timeout and redirect behavior configurable using the defaults and scope rules in Configuration below. Explain what happened when a redirect was not followed. Deliver proxy and certificate settings after the basic request workflow is stable; keep their eventual behavior specified centrally.

The **Details** tab describes the completed execution snapshot: request start time, method, resolved URL with secrets masked, selected environment name, effective request headers and their sources, request body size/content type, HTTP version, status or transport error category, total duration, response bytes read/displayed, charset, truncation, timeout setting, redirect policy, and observed redirect steps. Include cancellation state and whether the response was partial. Show connection/TLS/timing breakdowns only when actually measured by the transport; otherwise label them “Not available.” The Body and Headers tabs remain focused on the response payload and response headers. Editing the request or changing environments after sending must not rewrite this record.

Acceptance: configure an authenticated JSON POST using an environment token, fill a path variable and repeated query parameters, send it, inspect the formatted response, and switch environments without rewriting the request. Cancelling one request must not change another tab's state.

**Phases 1 and 2 are the recommended next release.** Together they address the usability gap shown in the screenshots.

## Phase 3 — Richer and more reliable discovery

- Extend endpoint metadata with module, handler signature, source location, path/query/header bindings, body type, media types, and routing conditions.
- Detect `@PathVariable`, `@RequestParam`, and `@RequestHeader` names, required flags, and defaults. Populate editable request controls from this metadata.
- Generate JSON body examples from DTO types, with cycle/depth limits and visible uncertainty for polymorphic or unresolved types. Never present generated examples as guaranteed valid business data.
- Support custom composed Spring mappings and aliases, with regression coverage for inherited interfaces and overridden handlers.
- Resolve Spring property placeholders from explicitly selected configuration sources, including nested defaults. Keep the HTTP request environment distinct from the application's active Spring profile. Show unresolved values instead of guessing runtime configuration.
- Add incremental discovery after source changes. Preserve cached results during indexing and label stale results until refreshed.
- Offer dependency-controller discovery as an opt-in, cancellable background scan with package filters. Keep source origin and routing conditions visible; equal method/path pairs can represent distinct handlers and must not be silently collapsed.
- Add separate discovery adapters for Kotlin Spring, then JAX-RS/Quarkus if needed. An optional OpenAPI import can cover APIs whose source is unavailable.

Acceptance: validate fixtures across multiple modules, imported constants, composed annotations, inheritance, overloaded handlers, property defaults, and changed source files. Show discovery limitations in context rather than claiming the source list equals the running application's route registry.

## Phase 4 — Collections, history, and reuse

- Save a discovered or manually authored request into a Bruno collection, with folder selection and collection-relative references.
- Load and edit existing Bruno requests using the existing parsers/serializers through a defined bridge. Preserve unsupported fields and scripts when editing; never silently remove them.
- Add Copy as cURL and Import cURL, with explicit handling of bodies, quoting, repeated parameters, and file references.
- Implement the History panel, retention, filtering, comparison, and deletion behavior specified below. Redact sensitive values and omit request and response bodies by default.
- Add favorites and rerun/duplicate actions. Imported history creates a request draft rather than immediately sending it.
- Clearly identify which requests use direct HTTP execution and which use Bruno execution. Bruno scripts, authentication, cookies, and assertions need the Bruno runner's semantics.

Acceptance: discover a controller endpoint, customize it, save it as a Bruno request, reload it, and reproduce the request. Confirm compatible files round-trip without dropping data and exported artifacts omit secrets by default.

## History — Phase 4 specification

### Retention and storage

- History is local to the project and enabled by default when Phase 4 ships. Record each executed request once when it succeeds, returns an HTTP error, fails at transport level, times out, or is cancelled. Validation errors before dispatch do not create entries.
- Defaults: **1,000 total entries**, **30-day retention for unpinned entries**, and **100 MiB total project history storage**. Age retention means elapsed 24-hour days from the recorded execution timestamp. Prune the oldest unpinned entries when any limit is reached, after writes and on project open. Timestamps are stored in UTC; display and date filtering use the IDE user's local timezone.
- A user may pin up to **100 entries**, with a combined pinned-storage cap of **20 MiB**. Pins count toward the overall limits but are exempt from automatic age/count eviction. Refuse a pin that would exceed its limits with an explanation; never silently delete pinned entries. If pruning cannot make room, pause new history capture and show an action to manage storage. Sending requests continues normally.
- Persist history outside the repository in project-associated IDE local storage, identified by a stable project ID. Do not mix it with shareable collection files or IDE settings synchronization. Surface the storage location and current entry/byte usage in Settings.
- Store redacted execution metadata, unresolved request templates, and ordinary parameter/header values needed to reconstruct a draft. Preserve secret references where available. Redact credential headers and configured sensitive query/header names; never store resolved secret values. Unknown sensitive data cannot be inferred reliably, so provide an editable redaction-name list and explain what capture includes.
- Request-body and response-body capture are independently opt-in and both default to Off. When enabled, each body is limited to **256 KiB per entry**. Record omitted/truncated status explicitly. Never capture resolved secrets through a body; sanitize known secret values, and omit the body when safe capture cannot be established. Binary/file uploads retain metadata only. Turning capture off affects future entries; removing previous captures is an explicit deletion action.
- History stores references to environment identities plus their display names at execution time; it does not copy whole environments. Requests opened from history use current environment values and clearly identify missing/deleted profiles or body content that was not recorded.

### Panel layout and navigation

- Selecting **History** opens a dedicated view with a top search field, filter controls, and a Clear menu; a history list occupies the main area and a read-only execution preview occupies the detail area.
- Default grouping: **Pinned**, **Today**, **Yesterday**, then calendar dates, with newest first. Offer **Group by Endpoint** as an alternative: module/controller/handler for discovered APIs, saved-request identity for collection requests, and draft identity for manual requests. Distinct handlers sharing a URL stay distinct.
- Wide layout columns: pin, local time, HTTP method, path, environment, status/outcome, duration, and response size. Put the full redacted URL, module/controller, and failure details in the row tooltip and preview. Status includes explicit Cancelled/Timeout/Connection Error labels.
- Below the shared 550-logical-pixel breakpoint, use compact two-line rows and open preview as a separate view with **Back to History**, preserving filters, selection, and scroll. Above it, use a resizable list/detail split.
- Clicking a row previews the immutable captured request and response summary. Enter or **Open as Request** creates/restores a draft without sending. Use a separate **Send Again…** action that opens a draft for review and focuses Send; it never performs an automatic request from the history list.
- History pinning is a single filled/outline pin toggle on the row and context menu. A pin preserves that exact execution record. Saved-request favorites remain a separate collection feature; do not provide an ambiguous second star toggle on history rows.

### Search, filters, and comparison

- Search is case-insensitive across redacted URL/path, method, controller/handler, environment name, and outcome. Whitespace-separated tokens use AND semantics. Do not search secret values or response bodies.
- Combine search with method, environment, outcome/status class, date range, endpoint, and Pinned Only filters using AND. Date ranges include both selected local calendar dates. Show active filter chips, a visible/total count, and Clear Filters; clearing filters never deletes history.
- Retain filters when opening a preview or request. If no entries match, offer Clear Filters. If history is empty, show “Requests you send will appear here” with **New Request**; if capture is disabled, show **Enable History**.
- Permit selecting exactly two entries with the same endpoint/request identity and choosing **Compare**. For discovered routes, use the identity rules from Phase 1 rather than comparing current URLs. Explain why Compare is unavailable for incompatible identities.
- Comparison is read-only, oldest on the left and newest on the right, with labeled timestamps and environments. Compare method, redacted resolved URL, parameters, effective headers, body (if captured), outcome/status, response headers/body (if captured), duration, and size.
- Show textual diffs with optional JSON formatting. Preserve raw comparison as a choice. Display “Body not recorded” or “Truncated capture” where applicable; do not fetch missing content or claim equality for omitted data. Secrets stay masked in previews, copy actions, and comparison.

### Clear and delete behavior

- Provide **Delete Selected**, **Clear Matching Filters**, **Clear Date Range…**, **Clear This Endpoint**, **Remove Captured Bodies**, and **Clear All History…**. Operate only on the current project's history.
- Before bulk deletion, show the scope, exact entry/body count, pinned count, and whether pins are included. Pinned entries are excluded by default; deleting them requires selecting **Include pinned entries**. A request to delete an explicitly selected pin also requires confirmation.
- Cancellation leaves history unchanged. Confirmation removes the selected records and their captured bodies, then refreshes counts and selection. This is permanent history deletion; it must not delete saved requests, collections, environments, or independent draft tabs. Existing draft copies remain usable.
- Remove Captured Bodies retains history metadata and marks the affected bodies as removed. Clear All means all project history within the confirmed pin policy, regardless of active search filters.

Acceptance: exercise retention boundaries, pinned quotas, storage exhaustion, all filter intersections, both layouts, comparison with missing bodies, and every delete scope. Confirm that a pinned entry survives automatic pruning, a redacted token never appears in the stored record, and deleting history leaves saved requests and drafts intact.

## Configuration — one Settings page

Provide **Settings/Preferences → Tools → Bruno**, searchable by “Bruno,” “timeout,” “history,” and “API discovery.” Sections are **General**, **Requests**, **Discovery**, **History & Privacy**, **Integrations**, and **Appearance & Shortcuts**. Controls display units, valid ranges, explanatory text, and an effective-value label where inherited. Unsupported future controls remain hidden until their owning phase ships.

### Scope and precedence

- The page has a scope selector: **IDE Defaults** or **This Project**. With no project open, show IDE Defaults only. Project-capable fields have **Use IDE default** checkboxes and show the inherited value.
- General precedence is built-in default → IDE default → explicit project override → supported request override. Only timeout and redirect options initially support request overrides through a per-request Advanced section. Other fields do not become request settings implicitly.
- Environments, variable values, secret references, drafts, history, package filters, and property-source selections belong to the project. The Settings page links to the environment manager rather than duplicating its values. The IDE keymap owns shortcuts.
- Execution snapshots retain the effective configuration used at send time. Applying settings changes subsequent executions; it must not silently alter running requests or rewrite history.
- Apply validates the whole edited section; invalid values keep the page open and identify the field. Cancel discards pending settings edits. Reducing retention/storage limits shows a pruning preview and requires confirmation before irreversible history deletion.

### Configuration inventory and defaults

| Setting | Default and validation | Scope | Delivery |
| --- | --- | --- | --- |
| Restore open tabs/drafts | On; recovery storage remains enabled to prevent silent data loss | IDE default + project override | Phase 1 |
| Show welcome hints | On for new projects; dismissible | Project | Phase 1 |
| Connect timeout | **15 seconds**, range 1–120 seconds | IDE → project → request | Phase 2C |
| Total request deadline | **30 seconds**, range 1–600 seconds; includes connection, redirects, and reading the body | IDE → project → request | Phase 2C |
| Follow redirects | **Off**; opt-in On permits up to 5 hops, configurable 1–10 | IDE → project → request | Phase 2C |
| Redirect credentials | Never forward Authorization/cookies to a different origin automatically; no HTTPS-to-HTTP automatic downgrade | Fixed behavior | Phase 2C |
| Response preview limit | **1 MiB**, range 64 KiB–10 MiB; saving a larger response streams to a chosen file | IDE default + project override | Phase 2C |
| Proxy | **Direct connection**; later choices: IDE proxy or explicitly configured HTTP proxy | IDE default + project override; credentials in credential store | After Phase 2C, before Phase 4 |
| TLS verification | **On**, with transport/default trust store; no global trust-all switch | Fixed baseline; custom trusted certificates and host-scoped client certificates configurable | After Phase 2C, before Phase 4 |
| Scan modules | All application modules; tree dropdown is a view filter over this scan scope | Project | Phase 1 |
| Include packages | All application packages when empty; editable qualified package prefixes | Project | Phase 3 |
| Exclude packages | None; prefix matches include subpackages and use package boundaries | Project | Phase 3 |
| Include test sources | Off | Project | Phase 3 |
| Include dependency controllers | Off; when enabled, require selected dependencies or package filters | Project | Phase 3 |
| Automatic discovery refresh | On; debounce source changes by 750 ms, range 250–5,000 ms | IDE default + project override | Phase 3 |
| Spring property sources | No assumed active profile; explicit source/profile selection | Project | Phase 3 |
| History enabled | On | IDE default + project override; data always project-local | Phase 4 |
| History count / age / storage | **1,000 entries / 30 days / 100 MiB**; ranges 100–10,000 / 1–365 days / 10–500 MiB | IDE default + project override | Phase 4 |
| History pin caps | Maximum **100 entries / 20 MiB**, also subject to overall history capacity | Fixed caps; effective byte cap cannot exceed configured history storage | Phase 4 |
| Capture request / response bodies | Both Off; when enabled, **256 KiB per body**, range 16 KiB–1 MiB | Project opt-in only | Phase 4 |
| Redaction names | Authorization, Proxy-Authorization, Cookie, Set-Cookie; query/header names token, access_token, refresh_token, api_key, apikey, password, secret | Built-in credential headers mandatory; additive IDE/project name lists | Phase 4 history, export when available |
| CLI executable / Node / Bruno desktop | Auto-detect with visible resolved location; allow explicit path and Test Configuration | IDE default + project override | Existing runner; configuration in Phase 2, desktop handoff in Phase 5 |
| Theme, fonts, method badges | Follow IDE theme and fonts; no independent theme picker | IDE inherited | Phase 1 |
| Shortcuts | Defaults in shared interaction rules; **Open Keymap Settings** link | IDE keymap | Phase 1 |
| Reset welcome hints | Explicit action only | Project | Phase 1 |

The connect timer limits connection establishment; the total deadline covers the entire exchange. They are distinct settings, not competing definitions of “timeout.” If the total deadline is shorter than the connect timeout, the total deadline wins; display that effective limit. Cancel closes active streams and transport work. Redirect hops share the same total deadline.

Include/exclude package rules apply to full qualified package prefixes (for example, `com.example.api` matches that package and its descendants, but not `com.example.apiv2`). Exclusions win. Changing scan configuration triggers a background rescan; the UI's Search and Module filters remain independent. Dependency discovery never expands silently to every JAR.

### Reset and migration

- **Reset Section to Defaults** resets the visible section in the selected scope as a pending edit. In IDE Defaults it restores built-in values; in This Project it removes overrides and restores inheritance. Display a diff before Apply.
- **Reset All Bruno Settings…** resets configuration in the selected scope after confirmation. It does not delete environments, credentials, request drafts, history, saved collections, or keymap customizations. These have separate explicit management actions. Retention changes still require the pruning confirmation described above.
- Migrating 0.2.0 retains the existing effective connect timeout (15 s), total deadline (30 s), redirect-off policy, and 1 MiB preview limit. Preserve environments and explain newly available overrides once. Schema migrations are versioned and must not silently discard unrecognized data.

Acceptance: verify inheritance, project isolation, request overrides, validation, Cancel/Apply, reset without data loss, reduced-limit pruning confirmation, and unchanged effective behavior after migration.

## UI and icon vocabulary

Use native IntelliJ action icons when a compatible public icon is available. The following table specifies the visual meaning and presentation; exact SDK constants must be checked against the supported IDE versions during implementation. If an icon has no suitable supported equivalent (for example, a flow graph), provide an original theme-aware SVG. Do not copy another plugin's artwork.

| Action or item | Icon / visual meaning | Presentation |
| --- | --- | --- |
| Send | Right-pointing run triangle | Primary icon + **Send** label; active shortcut in tooltip |
| Stop | Square | Icon + **Stop** label, next to execution spinner |
| Refresh APIs | Circular refresh arrows | Icon-only toolbar action with tooltip; refresh spinner while busy |
| Save Draft / Save | Save/disk | Icon + label in request toolbar; icon-only when compact, with full action name in tooltip |
| APIs | HTTP endpoint/network | Navigation icon + label |
| Project / Module / Controller | Native project, module, and class symbols | Tree icon + name + count; never ambiguous unlabeled nodes |
| Collections | Folder | Navigation icon + label when available |
| Flows | Connected-node graph | Navigation icon + label |
| History | Clock | Navigation icon + label |
| Environments | Layered configuration/sliders | Navigation icon + label; current environment is a labeled dropdown |
| Settings | Gear | Icon-only toolbar/menu entry with tooltip |
| Add Row / New Request | Plus | Icon-only in row/tab toolbars; **New Request** icon + label in empty states |
| Delete Row | Minus | Icon-only row action; distinguish from permanent deletion |
| Delete Draft / Delete History | Trash | Icon + descriptive label in menus/dialogs |
| Duplicate | Overlapping documents | Icon + label in context menus |
| Pin / Unpin | Filled / outlined pin | Icon-only toggle with stateful tooltip and accessible pressed state |
| Show / Hide Secret | Eye / crossed-out eye | Icon-only beside the masked field; stateful accessible name |
| Copy | Overlapping pages / clipboard | Icon-only beside values; context menu includes descriptive label |
| Compare | Side-by-side diff | Icon + label for selected history entries |
| Open Source | Navigate-to-code arrow | Icon + label in menu; icon-only toolbar equivalent |
| Back to APIs / History | Left arrow | Icon + label in compact layout |
| Open in Bruno | External-window arrow | Primary icon + label in fallback panel |
| Search / Clear Search | Magnifier / small close glyph | Search-field leading/trailing icons; clear has an accessible name |
| Filter | Funnel | Icon + active count; tooltip lists active filters |
| More | Ellipsis | Icon-only overflow menu; low-frequency actions live here |
| Detached / Warning / Failure | Broken link / amber warning / error symbol | Icon plus explanatory text; color alone never communicates state |

All toolbar and tree icons use **16 × 16 logical-pixel artwork**, scaled by the IDE for HiDPI. Regular toolbar actions have at least a **24 × 24 logical-pixel hit area**; primary buttons use standard IDE button padding. Do not shrink icons to fit a narrow layout: move secondary actions into More. Empty-state symbols use **32 × 32 logical pixels**. Do not mix arbitrary 20 px toolbar artwork into the same row.

Use icon + label for primary actions and navigation, icon-only for familiar secondary toolbar actions with tooltips, and label-only for section tabs, form labels, and table column headings. Under the compact layout, keep Send/Stop and Back labeled; Save may become icon-only. Disabled actions retain a tooltip explaining why they are unavailable. Unsupported future features remain hidden unless deliberately shown as disabled roadmap items.

### Loading, empty, detached, and stale states

- **Scanning:** on first discovery, show an IDE indeterminate spinner with “Discovering APIs…” in the tree area. During indexing show “Waiting for indexing…”; keep New Request enabled. During refresh retain the old tree, selection, and scroll, display a spinner in the explorer toolbar, and disable duplicate refresh submissions. Provide a small Stop Scanning action; cancelling keeps the previous results.
- **Partial scan failure:** retain usable cached results, show a warning summary with Retry and View Details, and mark affected branches. Never replace an existing populated tree with a generic empty-state screen after a failed refresh.
- **Empty project:** show a muted 32 px endpoint icon, “No supported controllers found,” one sentence explaining supported discovery, and **New Request** / **Refresh APIs** actions with an import-project help link.
- **No search matches:** show a 32 px magnifier, “No APIs match these filters,” active scope text, and Clear Search / All Modules actions. Do not reuse the no-controller message.
- **No request open:** show a document icon, “Select an API or create a request,” and New Request. **No response yet:** show a muted send icon and “Send this request to view its response.” No decorative illustrations or repeated log lines.
- **Detached draft:** display a theme-aware amber-tinted banner below the request tab strip, with a broken-link/warning icon, “Source endpoint no longer found. Your draft is preserved,” and **Relink…** / **Keep as Manual Request** actions. The banner wraps at narrow widths. Keep the draft editable and allow sending if its effective URL is valid; detachment is not a transport error.
- **Stale discovery:** display a small clock badge and accessible “Out of date” description on affected tree entries, plus “Showing results from [time] — changes pending refresh” in the explorer status area. Do not dim text below accessible contrast or remove endpoints while indexing is underway. Offer Refresh; clear the stale treatment only after successful reconciliation.
- **Draft versus changed source:** when refreshed source changes an open endpoint, show a separate “Endpoint changed in source” banner with Review Changes / Keep Draft. Do not silently change an edited URL or reuse the detached-draft message.

Acceptance: review icon meanings, sizes, hover/disabled/pressed states, keyboard focus, text contrast, and screen-reader labels in both themes and across supported display scales. Capture the first-load, refresh, no-results, detached, stale, sending, and failure states in UI verification.

## Phase 5 — Make Bruno flows useful inside the IDE

- Add selected saved requests to a flow and support New Flow from Selected APIs after those APIs have been saved into a collection.
- Show flow runs as structured node results with timing, errors, assertions, and input/output details, while retaining access to raw logs.
- Bring environment selection and variable handling into the flow UI with explicit mapping to Bruno collection environments. Do not silently apply direct-request settings to CLI execution.
- Investigate hosting the existing React flow canvas through JCEF. First isolate Electron dependencies, define the IDE bridge, and verify host availability and lifecycle behavior. Keep an explicit external-editor fallback until that path works.
- When the embedded canvas is unavailable or fails to initialize, show a short reason and a primary **Open in Bruno** button, with **Retry Embedded Editor** as a secondary action when relevant. Save pending flow edits before handoff and pass the collection and flow file through a verified desktop-launch integration. If no supported file-opening integration exists, launch Bruno and show **Copy Flow Path** / **Reveal Collection** rather than claiming the selected flow opened. If Bruno is not configured, the same button opens a desktop-executable chooser; cancellation keeps the IDE draft intact. The fallback opens an editor and never starts flow execution automatically.
- Integrate debugging controls only after the bridge supports the shared engine's pause, step, continue, breakpoint, and cancellation semantics.

Acceptance: saved requests form a runnable flow, failures identify the responsible node, and cancellation works across IDE and runner processes. A canvas/debugger must pass integration checks before being advertised as available.

## Implementation structure

The current `ApiExplorerPanel` owns discovery, layout, environment editing, HTTP execution, cancellation, and response formatting. Split these responsibilities before adding more controls:

| Component | Responsibility |
| --- | --- |
| Discovery service and adapters | Background indexing, endpoint metadata, warnings, invalidation |
| API explorer | Tree, filtering, selection, source navigation |
| Request workspace and draft store | Independent tabs, edits, restoration, endpoint-to-draft association |
| Parameter/header editor | Ordered rows, toggles, validation, bulk editing |
| Environment service | Profiles, precedence, interpolation, secret references, migration |
| Configuration service and Settings page | IDE/project/request precedence, validated defaults, reset, effective-value snapshots |
| Execution service | Immutable request snapshot, transport adapters, cancellation, limits |
| Response viewer | Summary, formatted/raw body, headers, errors, downloads |
| History store and viewer | Project-local capture, redaction, retention, pins, search, comparison, explicit deletion |
| Collection/flow bridge | Existing Bruno file formats and execution capabilities |

Use the source-file/handler/mapping identity defined in Phase 1 separately from each request draft's UUID. Persist versioned draft/environment data with migrations. Perform source scans, file operations, network work, and formatting on background workers; update Swing controls on the IDE event thread. Dispose listeners, tasks, editors, streams, and subprocesses when their owner closes.

Use the installed plugin ID for upgrades so existing installations update in place. Review the current descriptor naming warning before any Marketplace publication; do not casually change identity and create a second installation.

## Verification and delivery

- Test draft isolation and restoration, environment precedence and migration, secret omission, URL encoding, header overrides, and controller discovery edge cases.
- Add interaction coverage for Send shortcut scoping and duplicate-send prevention, dirty markers versus saved baselines, resolution preview/error placement, secret reveal expiry/copy notifications, lossless bulk toggling, duplicate-key rules, module/search intersection, and breakpoint navigation without cancelling requests.
- Verify the dedicated History retention/filter/diff/delete rules, Settings scope/default/reset/migration rules, and UI/icon state matrix before releasing their owning phases.
- Use local HTTP fixtures for JSON, forms, multipart, redirects, different charsets, large bodies, timeouts, and cancellation. Do not use a developer's application endpoints as automated test targets.
- Exercise UI workflows in a sandbox IDE at narrow/wide widths and both themes. Check keyboard navigation, labels, focus, empty states, and tool-window reopening.
- Check the supported IDE baseline and the user's IntelliJ 2026.2 installation; a successful compile alone does not establish compatibility across versions.
- For each phase, provide a versioned installable ZIP, updated setup/migration notes, screenshots of completed workflows, and a concise list of verified behavior and known limits.

The next implementation milestone should demonstrate one complete workflow: browse a module/controller tree, open two independent requests, configure headers and an environment, send a request, inspect its response, and restart without losing drafts.

### Follow-up usability fixes

Ctrl+W is scoped to components within Bruno. Explorer actions are Refresh/New Request plus a menu containing Stop, Expand, Collapse, and Notes; the Requests navigation button has its own row. Wide editors offer a persistent orientation toggle. Drafts retain section selection, narrow response tabs retain status across resize, and empty query tables explain how to add parameters.

### Performance correction — 0.3.1

The installed IDE log showed repeated StackOverflowError failures inside DarculaTabbedPaneUI.WrappingLayout. Narrow request sections now use SCROLL_TAB_LAYOUT plus a section dropdown to keep Advanced and other sections accessible. Reopening an active request avoids detaching the native editor hierarchy; title updates during editing are coalesced. Regression tests cover hierarchy stability and narrow section reachability. Installed-IDE responsiveness still needs confirmation after upgrading.

### Freeze regression correction — 0.3.2

0.3.1 did not resolve the installed IDE freeze. Follow-up logs still contained recursive DarculaTabbedPaneUI.WrappingLayout calls with SCROLL_TAB_LAYOUT. All five tab groups now use BrunoTabbedPane with a plugin-local BasicTabbedPaneUI delegate, retained on updateUI/theme changes. Verification includes graphical overflow/navigation/resize/theme-refresh checks against the installed IU-262.10968.63 SDK and its bundled runtime, alongside the full regression suite. A simple isolated Darcula tab fixture did not reproduce the original crash; the installed IDE logs establish that failure, while the regression checks assert the failing delegate is absent throughout the workspace.

### UI and flow file operations — 0.3.3

Addressed screenshot findings: compact conditional Auth controls, readable selected tabs, consistent tree background, bounded restored explorer width, response-pane shrink behavior, endpoint-opening guidance, and a labeled Environments button. Base URL defines {{baseUrl}} and remains profile-specific. The flow trash action previously cleared output; Delete Flow and Clear Output are now distinct labeled actions. Flow/collection creation uses IntelliJ write commands and a folder chooser; OS-level permission errors are reported rather than changing permissions. New flows contain valid graph data instead of the previous invalid flow block. Tests cover create/discover/duplicate/delete, invalid names/non-flow deletion, conditional Auth controls, and graphical overflow checks. The generated starter is also parsed and run by the Bruno CLI.

### Review and robustness update — 0.3.4

Implemented the requested per-draft session history (10 entries/5 MiB budget, newest response retained), response comparison, redacted captured cURL, URL/body variable-name completion, non-modal operational notifications, non-Spring guard, cached/automatic discovery, and idle settings validation. Project-wide persisted history remains a separate planned feature. Closed-draft response release, protected LRU pruning, cached dirty comparisons, VFS flow discovery, post-commit credential cleanup, and dedicated state files address review defects. See REVIEW_REPORT.md for the finding-by-finding reconciliation and limitations.

### 0.4.0 — global workspace and developer integrations

Implemented IDE-wide API profiles and shared selection, project-profile migration, controller/method gutter actions, wrapped transport errors, compact response actions, visible splitters and request-local variable guidance. Added automatic image/passive HTML/secure XML viewers, source-derived OpenAPI export, last-submitted-input duplication and reusable Bruno QuickJS hooks in a cancellable Node worker. Added an opt-in authenticated loopback companion for direct MVC adapter invocation and annotated scheduled/XXL task triggering. The runtime companion is required; no JVM instrumentation or silent security bypass is performed by ordinary HTTP requests. See README.md and spring-bridge/README.md for prerequisites and supported semantics.

### Review v2 follow-up

Compact mode uses one section picker and one global environment selector; the tab strip is hidden. Discovery preserves group expansion without expanding every endpoint, uses concise counts and retires the opening hint after first use. Script hooks reuse a project-scoped worker with cancellable transactions and fresh script scopes, avoiding repeated Node startup and extra IDE-executor pipe reads.

### 0.4.1 visibility follow-up

Compute response space from actual font metrics and controls; account for clipped viewports. Reveal failures automatically without replacing the saved split preference. Use whole-tool-window native fixtures with large fonts, including explorer presence and error containment checks.

### 0.4.2 HTTP sessions

Automatic, transient cookies scoped to project/profile/origin support normal session and CSRF authentication across request tabs. More offers explicit cookie clearing; Details identifies injected cookies without revealing values, and cURL masks cookies/CSRF headers. No automatic token endpoint calls or server validation bypass.

### 0.4.3 value readability

Resizable full-value editing from table cell/toolbar/shortcut; visual wrapping and explicit Apply/Cancel. Response Wrap lines defaults on and remembers the preference. Copy/save retain original characters, including long token strings.

### 0.4.4 flow runner setup

Provide Runner settings directly in Flows and a browsable IDE-wide CLI file/checkout field. Honor configured Node for JS entry points, resolve off the EDT, and wrap startup errors with concrete configuration guidance.

### 0.4.5 run history

Flows has Execution history and Console tabs. A run picker retains recent project-session runs; each step exposes Input/Output/Details with before/after context and HTTP data where captured. Preserve repeated/nested invocations, select failed steps, retain cancellation/startup failures, and bound data/console retention with explicit clear/limit behavior.

### 0.4.6 JSON upload

Import JSON is visible in both empty and populated Flows. Choose source and destination, validate in the background, create a uniquely named native flow and select/open it without running it. Errors use notifications; cancelling either picker changes nothing.


### 0.4.7 review follow-up

Large run histories load and format in cancellable background jobs, with batched list updates and bounded previews. Deferred automatic discovery rechecks visibility. Secret-cell edits preserve unchanged stored credentials and follow reordered columns. Script EOF ordering, report robustness, retention accounting and panel cleanup were reviewed and fixed; see CODE_REVIEW_0.4.7.md.


### 0.4.8 Serena follow-up

Bound expanded variable output and reuse resolved values, cancel obsolete response preview jobs, release disposed response documents/history, reject pathological JSON/XML nesting before formatting, and support Stop throughout flow-runner setup. See CODE_REVIEW_0.4.8.md for regression coverage and practical limits.


### 0.4.9 Stitch review

Consolidated request commands and explicit bounded resolved URL; compact global environment captions and native search hints; width-aware flow toolbars and stacked collection environment selection; narrow empty-state actions. Design source and validation are in UI_REVIEW_0.4.9.md.


### 0.4.10 platform follow-up

Environment updates are event-driven, with no idle per-tab settings polling and no selector rebuild for session-only changes. Cached Close actions retain their original request; delayed focus respects active ownership. Lifecycle and cancellation changes follow Context7 SDK guidance and JetBrains inspections; details are in PLATFORM_REVIEW_0.4.10.md.
