# Bruno Flows for IntelliJ IDEA

This plugin provides two tabs in the **Bruno Flows** tool window: **APIs** for discovering and sending requests to Java Spring controller endpoints, and **Flows** for running saved Bruno flow files through the CLI.

Version **0.4.10** implements the core request workspace from the [usability and capability plan](UX_PLAN.md). Project-wide persisted history, collection saving, richer discovery, and visual flow editing remain planned.

## Browse and send controller APIs

1. Open your Java Spring project, import its Maven/Gradle configuration, and wait for indexing.
2. Open **Bruno → APIs**. Refresh discovers a **project → module → controller → endpoint** tree. Search and the module filter work together. Double-click an endpoint, or press Enter, to open a request tab.
3. Click **Environments…** beside the global environment selector. Set the profile's Base URL (for example `http://localhost:8080`), variables, and default headers, then choose **Save Environments**. Start your application before sending.
4. Configure **Params**, **Path**, **Headers**, **Auth**, **Body**, or request-local **Variables**. Body modes include JSON, Text, Form, and Multipart. Use the file picker for multipart file rows.
5. Click **Send**, or press **Ctrl+Enter / Cmd+Enter** inside the request editor. The button becomes Stop during execution.
6. Inspect **Body**, **Headers**, and **Details** below the request. Details describe the captured request/environment/settings and response metadata. Editing a draft after sending does not rewrite that response snapshot.

Each endpoint has its own draft. A **•** prefix marks changes since **Save Draft**. **More…** includes duplicate, pin, revert, close/reopen, source navigation, and explicit **Send & Download**. Closing a tab retains its draft; discarding is a separate action. Drafts and open tabs restore on project reopen; responses remain session-local. Saved drafts currently live in IDE project state, not Bruno collection files.

Below **550 logical pixels**, the tool window shows either APIs or the request workspace. **Back to APIs** preserves drafts and running requests. Wider windows show a resizable explorer beside the workspace.

### Variables, headers, and secrets

A route `/users/{id}` opens as `{{baseUrl}}/users/{{id}}`. Set `id` in Path or an environment. `baseUrl` comes from the selected environment's Base URL field. Variable precedence is global defaults → selected profile → request-local variables → script session variables; configured path values override matching URL placeholders. Path and query values are URL-encoded. Body interpolation is textual: JSON values must still be valid JSON.

The italic line below the URL previews the resolved address, masks secret values, and reports unresolved variables. Missing variables in active fields prevent sending. Inactive body/auth fields and overridden headers do not block execution.

Headers inherit from the environment. A request-level header replaces the inherited group with the same case-insensitive name; a disabled request row suppresses that inherited group. Repeated request headers and query keys are preserved. Auth supports Bearer, Basic, and API key in a header or query parameter.

Tables support enabled/key/value/description rows. **Bulk Edit** uses `key=value` lines, `# ` for disabled rows, and a literal tab before descriptions. Escape backslashes, newlines, tabs, or equals signs with `\\`, `\n`, `\t`, and `\=`. Apply validates the text; Cancel leaves rows unchanged. Duplicate header/query keys survive round trips; variable names must be unique.

Mark sensitive global or profile variables **Secret** to store their values in the IDE credential store. Drafts retain references such as `{{token}}`. The environment manager supports temporary reveal and copying with feedback. Global profile metadata is stored in IDE `options/bruno-environments.xml`; request bodies, headers, and literal auth values are stored in the local `.idea/bruno-requests.xml` file; use secret references for credentials. Existing plain-text profiles are migrated when edited and saved, without automatically guessing which values are secrets.

### Responses and settings

Response views show status, elapsed time, bytes read, text/JSON body, headers, and request details. JSON has Pretty/Raw display; body text can be searched, copied, or saved. Binary responses show a notice. Previews stop after the configured limit; **Send & Download** explicitly sends the request again and streams its response to a new file.

Open **Settings → Tools → Bruno** for project overrides or IDE defaults:

| Setting | Default |
| --- | --- |
| Connection timeout | 15 seconds |
| Total deadline, including reading the body | 30 seconds |
| Follow redirects | Off |
| Maximum redirect hops | 5 |
| Response preview | 1024 KiB |
| Restore request tabs | On |

Request **Advanced** settings can override connection timeout, total deadline, and redirect behavior. Enabled redirects follow only the same origin; cross-origin redirects and HTTPS downgrades remain visible for manual review. Resetting settings preserves drafts and environments.

These environments and settings apply to the APIs workspace. Flow execution still uses the collection's Bruno environment selected in the Flows tab.

### Discovery coverage

Discovery uses IntelliJ Java PSI, including standard Spring `@Controller` / `@RestController`, `@RequestMapping`, and GET/POST/PUT/PATCH/DELETE mapping annotations. It combines controller prefixes with method paths, expands path/method arrays, resolves Java string constants, and reads inherited mappings from interfaces or superclasses. Test source roots are excluded.

This version supports Java Spring annotation controllers. Kotlin, JAX-RS, functional routing, runtime registration, application property placeholders, and advanced routing conditions are not inferred. Unresolved route constants and direct custom composed mappings appear in **Discovery notes** for manual review. The list is source discovery, not a guarantee that every endpoint is active in the currently running Spring profile.

The APIs tab sends HTTP requests directly; it does not run Bruno scripts or assertions. Use the Flows tab for Bruno flow execution.

## Saved flow execution

- Finds `.flow.bru`, `.flow.yml`, and `.flow.yaml` files inside Bruno collections.
- Runs the selected flow and streams CLI output in the **Bruno Flows** tool window.
- Accepts an optional Bruno environment name and passes it to the CLI as `--env`.
- Stops a running flow and its child processes.
- Uses the repository's local CLI when this Bruno checkout is open, then looks for a project or collection local CLI, and finally falls back to `bru` on `PATH`.

## Install in your regular IntelliJ IDEA

Building the plugin or running `runIde` does not install it in your regular IDE. `runIde` opens a separate development sandbox.

1. Build the archive with `./gradlew buildPlugin` from this directory.
2. In your regular IntelliJ IDEA, open **Settings → Plugins**.
3. Open the gear menu and select **Install Plugin from Disk…**.
4. Select `build/distributions/bruno-intellij-plugin-0.4.10.zip` without extracting it. Use this same action to update an installed older version.
5. Restart the IDE if prompted, then open a project and choose **View → Tool Windows → Bruno**. You can also search for **Bruno: Open Flows** using Find Action.

If the tool window is missing, check that **Bruno Flows** appears and is enabled under **Settings → Plugins → Installed**.

See JetBrains' [plugin installation instructions](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk).

## Try it in a development IDE

Open a terminal in this directory and run:

```sh
./gradlew runIde
```

The Gradle build uses a Java 21 toolchain. If it is not installed, the Gradle Foojay resolver downloads one automatically.

In the sandbox IDE, open a project containing a Bruno collection, then choose **View → Tool Windows → Bruno**. Select a flow and click **Run**. The selected flow runs with its collection directory as the working directory.

The Flows tab requires a CLI with this fork's `--flow` support installed in the project/collection or available on `PATH`. From the Bruno monorepo, the plugin invokes `packages/bruno-cli/bin/bru.js` using Node.js. The APIs tab does not require the CLI.

To build a distributable plugin archive:

```sh
./gradlew buildPlugin
```

The ZIP is written under `build/distributions/`.

## Verification

Run `./gradlew test buildPlugin verifyPluginStructure`. The plugin and companion tests cover discovery, state persistence, bulk edits, request composition, local HTTP exchanges, cancellation/deadlines, and responsive UI behavior. UI test snapshots are generated under `build/ui-snapshots/`; they do not replace checking the installed plugin in your IDE. The build targets IntelliJ 2024.3 APIs and Java 21.

The request **More…** menu switches the wide layout between stacked and side-by-side request/response panes. This preference persists per project. Each draft remembers its selected request section. Narrow response tabs show the captured status, sending state, or “No response”. Ctrl+W closes a request only while focus is inside Bruno.

Version 0.3.1 avoids the Darcula wrapping-tab layout recursion seen in IntelliJ logs. Narrow editors use scrolling tabs and a section dropdown. Reopening the active request keeps the native editor hierarchy attached, and repeated draft-title updates are coalesced during editing.

### Freeze regression verification

Version 0.3.2 uses a plugin-local BasicTabbedPaneUI for every tab group. Changing the scroll/wrap policy in 0.3.1 was insufficient: IntelliJ 2026.2.3 still used DarculaTabbedPaneUI.WrappingLayout, which recursively revalidated overflowing tabs in the installed IDE logs. The local delegate survives theme refreshes and uses the active UI defaults for colors/fonts.

To verify against an installed IDE and its bundled runtime:

```sh
./gradlew -PbrunoIdePath=/snap/intellij-idea/current test
./gradlew -PbrunoIdePath=/snap/intellij-idea/current -PbrunoGuiTests :test --tests '*TabLayoutRegressionTest'
```

The second command opens temporary regression windows. It exercises overflowing custom tab headers, repeated request/section selection, resizing across 550 px, and theme refreshes. The local-IDE build option uses Java 25; the default distribution still builds against 2024.3 APIs with Java 21.

### Environments and flow file actions (0.3.3)

In an API request, click **Environments…**, choose Local (or create a profile), and set **Base URL** to `http://localhost:8080`. This defines `{{baseUrl}}`. Add named values in Variables, for example `token`, then use `{{token}}` in headers, URLs, authentication, or bodies. Choose **Save Environments** before sending.

The Flows **Env** field selects an environment defined in the Bruno collection; it is separate from API environments. Create collection environments in Bruno or the collection's environment files.

**Create new flow** asks for a collection folder and uses an IntelliJ write command to create a valid Start → End flow. Choose a writable folder. Collection folders chosen outside the project are remembered for discovery. Existing files are never overwritten. A folder with OpenCollection metadata keeps its existing marker. **Delete Flow** now deletes the selected flow after confirmation; **Clear Output** only clears runner output. Both actions are labeled. A running flow must be stopped before deletion.

Auth fields now appear only for the selected authentication mode, retain ordinary control heights, and scroll as necessary. Request/response panes can shrink to fit the window. Tab foregrounds/backgrounds follow the IDE theme while retaining the overflow-free local layout. The explorer defaults to a smaller share of the window and explains how to open an endpoint.

### Review and robustness update (0.3.4)

- Controller discovery caches immutable results, refreshes 500 ms after Java PSI changes, and explains when Spring Web dependencies are absent. Editing JSON request bodies does not trigger endpoint scans.
- URL/body fields suggest variable names after `{{` or with Ctrl+Space. Values and secrets are never shown in suggestions.
- Responses retain up to 10 session entries within a 5 MiB history budget (the newest response is always kept). Use **History** to select a response and its **More → Compare** action for a native body diff. Closing the draft clears history.
- **Copy cURL (redacted)** exports the captured initial request. Credentials are replaced with masks before shell quoting. Replace those masks locally when reproducing authenticated requests. Redirects are not replayed automatically by the exported command.
- Environment changes refresh idle Send validation within approximately one second. Send validates again before dispatch; unavailable saved secrets produce worker-side feedback.
- Operational errors appear in Bruno notifications and IntelliJ's Event Log. Destructive actions still request confirmation.
- Closed, clean, unpinned drafts are pruned toward 100 retained drafts. Protected work may exceed this soft limit. Dirty indicators compare fields against a cached baseline rather than serializing the body on each title refresh.
- Drafts migrate from workspace.xml into `.idea/bruno-requests.xml`; keep this local draft file out of version control. Version 0.4.0 imports legacy project profiles into IDE-wide storage as described below. Credential cleanup runs after a successful save, preserves shared references, and retries failed deletions on a later environment save.

The reconciled findings are documented in [REVIEW_REPORT.md](REVIEW_REPORT.md).

## Global environments and source gutter actions (0.4.0)

API profiles now live at IDE level, shared across projects. Use the single **Global environment** selector above the API workspace. **Environments…** manages Base URL (`{{baseUrl}}`), variables, secrets and default headers. All open API tabs use the selected profile; requests already running retain their execution snapshot. Request-local Variables remain available as overrides.

On first use, existing project profiles are imported with their secret references intact. Conflicting names are renamed with the source project name. Old project defaults are folded into their imported profiles rather than overwriting IDE-wide defaults. Global profile metadata is stored in the IDE configuration's `options/bruno-environments.xml`; secrets remain in the IDE credential store. Concurrent profile edits in another window are detected before saving.

Green gutter actions appear beside supported Spring controller classes and endpoint methods. Click a method action to open its matching request in Bruno; choose a route when multiple mappings exist. Class actions list that controller's endpoints. Reopening a route reuses its independent draft.

## Response viewers, export and last execution

The response viewer selects JSON, text, XML, HTML or a supported raster image automatically. Select a mode manually or choose **Raw**. XML formatting disables external entities. HTML preview is passive: scripts, styles, external resources and links are not activated; input is capped at 200,000 characters. SVG is viewed as XML. Raster images use the IDE's ImageIO formats (such as PNG/JPEG/GIF); decoding is limited to 16 megapixels. Save preserves original image bytes; Copy supports decoded image pixels.

Connection failures use a wrapped message pane and hide JSON formatting controls. The response-history **More** icon contains comparison and redacted cURL export.

The API explorer overflow menu provides **Export OpenAPI…**. Export uses source mappings and basic parameter/body/DTO/record types. It never includes captured requests or environment credential values. Unresolved property paths are recorded separately; handler variants are listed as metadata. Custom binding/validation/security rules require review before using the export as a complete API contract. The existing converter package imports OpenAPI; this feature adds an exporter.

After sending, **More… → Open last execution in new tab** copies the submitted input snapshot, including headers, parameters, body and scripts, instead of subsequent unsent edits. Shared environment values and scripts are resolved again; captured secret values are not copied into persistent draft literals. Last-execution inputs and response history are session-only and released when the draft closes.

## Bruno JavaScript hooks

**Scripts → Pre-request / Post-response** uses the existing `@usebruno/js` QuickJS runtime, including `req`, `res`, `bru`, tests and supported Bruno libraries. Regular HTTP requests do not require Node. Hooks require Node and a built local Bruno runtime package:

- In this monorepo, install dependencies and run `npm run sandbox:bundle-libraries --workspace=@usebruno/js`.
- In another project, install a compatible `@usebruno/js` package, or configure this checkout's `packages/bruno-js` directory under **Settings → Tools → Bruno → Bruno JS package directory**.
- The Node executable is configurable in the same settings page. Project-local `node_modules/@usebruno/js` is detected automatically.

Example post-response hook:

```js
bru.setVar('token', res.getBody().access_token);
```

Set a shared header in Global Environments to `Authorization=Bearer {{token}}`; later API requests in that profile can use the session token. Hook variable changes are shared for that profile during the IDE session and are not written to persistent profiles. Token-like session values are masked in previews/cURL. Persistent credentials should be configured as Secret variables in Global Environments.

A pre-request hook can use `req.setHeader`, `req.setBody`, `req.setUrl`, and `bru.setVar`. Missing variables that may be supplied by a pre-request hook are validated again after it runs. Hook scripts use the same Bruno API syntax as collection scripts; standalone hooks do not execute collection request chaining—use Flows for that. One Node worker is reused per project across hook phases and sends, with a fresh QuickJS script scope for each phase. Hook transactions are serialized fairly; waiting is interruptible and covered by the request deadline. Pipe readers use dedicated daemon threads rather than IDE executor tasks. Each phase has a 15-second output wait limit. Stop, timeout, channel failure, or project close terminates the worker; a later send starts a replacement. Changing the configured Node executable or runtime directory also replaces it. Startup diagnostics are bounded and captured before any request data is sent. Post-response failures retain the HTTP response and add diagnostic details.

## Development bridge and tasks

The explorer menu's **Development bridge…** exports an opt-in companion starter and Maven POM, manages its loopback connection/token, and tests connectivity. **Advanced → Invoke through development bridge** invokes the application's Spring MVC adapter while skipping HTTP filters/interceptors. Spring binding, controller advice and service/method proxies remain active. **Scheduled / XXL tasks…** lists and manually invokes annotated handlers in that application.

The companion must be installed and explicitly enabled in the running application. See [spring-bridge/README.md](spring-bridge/README.md) for setup, supported versions, invocation limits and cancellation behavior. It is not a debugger attach or a profiler. Bridge connection metadata is stored in IDE settings per project; its token stays in the credential store. Normal HTTP transport remains available.

### Review v2 corrections

Compact request editors use one section dropdown, with the tab strip hidden. The global selector is the single API-environment display. Discovery preserves collapsed group identities without expanding endpoint leaves; counts use singular/plural labels and omit zero notes. The opening hint retires after the first request is opened. Script URL changes preserve query-row metadata, duplicate order and disabled flags. Malformed bridge replies report the expected companion response instead of a null-pointer failure.

### Request/response visibility fixes

The stacked layout respects a 280-logical-pixel response minimum and a 240-pixel request minimum. The 8-pixel divider is visible and draggable; double-click it to restore the 55/45 balance. Wide editors use Request/Response tabs below 560 logical pixels in height, or when the actual controls and font sizes need more room. These tabs also remain the default below the 550-pixel width breakpoint.

Body actions follow the selected mode: Format JSON appears in JSON mode, and Add file appears only in Multipart mode. Connection errors hide response-format controls, retain copy/save, and show a wrapped host/port message with application/Base URL recovery steps. A stopped or misconfigured application still needs to be started or corrected in Global Environments.

### 0.4.1 response visibility correction

Response space is calculated from the real heading, toolbar and font metrics, including the visible viewport. Automatic adjustment preserves the saved split preference. Restored connection failures start with balanced panes; newly failed sends bring the response into view and select its Body tab. Explorer reflow also uses current layout bounds instead of waiting for a resize event. Native-window tests cover the whole tool window with enlarged fonts at multiple heights, plus a real failed send.

### 0.4.2 cookies and CSRF login

Normal API sends automatically retain Set-Cookie values and send matching cookies on later requests in the same project, environment profile and exact origin (scheme, host and port). Cookie state is session-only and cleared when the project closes. Secure, path and expiry rules are honored; explicit Cookie headers override the jar. Redirects re-evaluate cookie eligibility. Profiles and origins do not share cookie jars, and bridge invocation does not participate in HTTP sessions. Retention is bounded to 32 jars per project, 128 cookies per jar and 8 KiB per accepted cookie header.

Use **More… → Clear session cookies for this environment** to reset the project's session. Cookie and CSRF headers are masked in captured cURL; injected cookies are identified without values in Details.

For the illustrated Spring login workflow:

1. Send `GET {{baseUrl}}/api/v1/auth/csrf`. Bruno retains its cookie automatically.
2. Use that response's fresh `data.token` as the login request's `X-CSRF-TOKEN` header (or the returned `data.headerName`, if different). Keep the same environment, host and port.
3. Send the login request with its JSON body. Changing the cookie/session or using an older token can still produce `CSRF_INVALID`.

To avoid copying tokens manually, configure this post-response hook on the CSRF request (requires the Bruno JS setup above):

```js
bru.setVar('csrfToken', res.getBody().data.token);
```

Then set the login header value to `{{csrfToken}}`. Fetch the token again after clearing cookies or restarting the project. The plugin preserves the session; it does not disable CSRF validation.

### 0.4.3 long-value readability

Select a row in Headers, Params, Path, Variables or another key/value table and choose **Expand value…** (or the expand icon at the end of its Value cell). Ctrl+Shift+Enter / Cmd+Shift+Enter opens the same editor while the table has focus. The resizable dialog wraps unbroken tokens, supports copying, and uses Apply/Cancel. Visual wrapping never inserts line breaks; Apply preserves row identity and other metadata. Secret values remain masked until explicitly shown; existing credential-store secrets are not loaded by this editor. Use the environment manager's secret actions to view stored credentials, or enter a replacement.

Response bodies now have **Wrap lines**, enabled by default. Long JSON token strings wrap within the viewer instead of requiring horizontal scrolling. This is visual soft wrapping: copied/saved text stays unchanged. The preference is remembered in IDE settings. The control is hidden for transport errors, images and HTML previews.

### 0.4.4 flow runner configuration

A flow appearing in the picker means discovery found its file. “Cannot run program bru” is a runner startup error. Use **Flows → Runner settings…** (also **Settings → Tools → Bruno**) to configure **Flow CLI path or checkout (IDE-wide)**. Browse to this fork's checkout, its `packages/bruno-cli/bin/bru.js`, an installed flow-enabled CLI package, or its executable. Set **Node executable** to an absolute Node path when desktop IntelliJ cannot find your shell's Node installation. JavaScript CLI entry points use this configured Node instead of relying on a shebang/PATH.

With no explicit path, discovery checks the project and collection for this checkout's CLI or a local CLI installation, then a sibling of the configured Bruno JS runtime package, and finally `bru` on PATH. This fork's `--flow` support is required; an upstream CLI version without it cannot run these flows. Resolution and process startup run on a worker thread. Flow output wraps long paths/errors, and the working directory remains the selected collection.

### 0.4.5 flow execution history and step I/O

After a run, **Flows → Execution history** selects the completed run. Choose a past run in **Run history**, then a step in the left list:

- **Input** shows the captured context before the node, its configuration/parameters, prior response and sent request when available.
- **Output** shows the resulting context, response and execution details.
- **Details** shows status, timing, errors/assertions, environment, collection flow path and captured console output.

Run summary shows the initial snapshot and final context. Every loop iteration and nested subflow step stays separate; failed steps are selected automatically. Console continues streaming during execution and remains available as a separate tab. Startup errors and cancellations also create history entries. A Start → End scaffold only executes those two nodes; add requests or use the provided smoke-test flow to inspect nonempty data.

History is **project-session only**: latest 10 runs within an approximately 5 MiB retention budget; **Clear history** removes it, and closing the project releases it. Known credential fields in captured JSON are masked. Raw CLI reports use temporary files and are deleted after capture. Reports larger than 2 MiB are omitted with an explicit message; individual text previews are limited to 128K characters and console tails to 256K characters. Custom console logging can contain whatever a script prints. Existing runs from earlier plugin versions cannot be reconstructed from their old console text; run the flow again after upgrading.

### 0.4.6 import flow JSON

Choose **Bruno → Flows → Import JSON…**, select your JSON file, then choose a destination Bruno collection folder. The action is available before any flows exist and beside Runner settings in the normal workspace. The imported file opens and is selected automatically; click **Run Flow** when ready. Import does not execute scripts or requests.

Try the [complete JSON scenario collection](../../examples/flows/scenarios/README.md), covering all 11 node types, login/token mapping, polling, CSV/JSON datasets, sub-flows, request overrides, and failure handling. It includes supporting requests, a local demo API, and expected input/output results. Choose `examples/flows/scenarios` as the destination collection to preserve its relative resource paths; configure `baseUrl` in the collection environment used by the flow CLI.

Both desktop exports (`type: "bruno-flow"`, `version: 1`, `name`, `flow`) and raw graph JSON (`nodes`/`edges`) are supported. Import validates strict JSON, graph structure, node types, unique IDs, connections, numeric positions and collection-relative references. It preserves configuration, viewport and node mappings while removing captured execution/UI state. Files over 10 MiB or deeper than 64 nesting levels are rejected.

Bru collections receive `.flow.bru`; OpenCollection YAML collections receive `.flow.yml` in valid JSON-compatible YAML syntax. A new destination folder receives collection metadata. Existing files are preserved; duplicate imports get a numbered filename. Referenced request/subflow/dataset files are not bundled and must exist in the destination collection when running. Relative references stay portable; internal absolute references become relative, and references escaping the destination are rejected.

Validation and serialization run on a worker; VFS creation uses IntelliJ's write-safe application callback and a write command. Import requires no Node/CLI; execution still uses the configured flow runner. Cancelling either chooser makes no changes.


### 0.4.7 correctness and performance review

- Preserve saved secrets when an unchanged table cell loses focus; secret editors follow reordered columns.
- Parse large flow histories and render bounded step previews in background jobs. Batch step-list updates, cancel superseded work, and cache retention byte counts.
- Reject malformed/deep reports safely, bound report reads, and redact credential fields in JSON-encoded response bodies.
- Preserve completed script results at worker EOF, read pipe messages in chunks, and avoid reusing a closing worker.
- Recheck tool-window visibility before automatic discovery, restore run controls after completion errors, and cancel pending imports and history tasks on disposal.

See [the follow-up code review](CODE_REVIEW_0.4.7.md) for findings and verification.


### 0.4.8 Serena-assisted review

- Memoize repeated variable expansion and reject expanded fields over 1 MiB of characters. Large literal bodies without variables retain their existing behavior.
- Cancel superseded response-rendering jobs and release response documents/history when closing request tabs.
- Limit JSON/XML nesting before pretty formatting; unsupported previews fall back to original raw text.
- Enable Stop during flow-runner resolution and prevent a cancelled run from launching.

See [the Serena review](CODE_REVIEW_0.4.8.md) for scope, regression evidence and limits.


### 0.4.9 Stitch-assisted UI review

- Consolidated Send, Save and More actions; labeled the resolved URL and kept long previews to one line with a redacted tooltip.
- Compact environment controls retain room for the selected profile; request inputs and search fields have accessible names and native empty-text hints.
- Flow selectors stack at narrow widths. Action toolbars and empty-state controls reserve their wrapped height instead of clipping.
- Existing native themes, breakpoints, draft state, request/response layout and execution behavior are preserved.

See [the UI review](UI_REVIEW_0.4.9.md) for the Stitch reference and verification.


### 0.4.10 Context7 and JetBrains platform review

- Request editors react to environment/session changes through lifecycle-bound message-bus subscriptions; idle per-tab polling is removed.
- Cached Close actions retain their original request target; queued URL focus requests ignore closed or inactive tabs.
- Flow history follows Disposer ownership. Disposed API workspaces release tab references and data providers.
- Cached discovery, route expansion and OpenAPI generation include cooperative cancellation checkpoints.

See [the platform review](PLATFORM_REVIEW_0.4.10.md) for SDK guidance, IDE findings and validation.
