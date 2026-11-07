# Flow authoring and request overrides

## CLI context

The CLI seeds `ctx` from the resolved environment, including `--env-var` arguments. Runtime variables take precedence over environment variables; this initial context takes precedence over defaults in the flow's configuration and Start node.

```sh
bru run --flow flows/e2e.flow.bru --env staging \
  --env-var buildId=9421 --env-var secretToken="$CI_TOKEN"
```

Scripts and assertions can read `ctx.buildId` and `ctx.secretToken` before any request runs. Request interpolation continues to use the same variables. Flow snapshots and JSON reports include context values, so treat those artifacts like other execution logs containing secrets.

## Copy a captured request

After execution, select a request node and open **Input / Output**. **Copy as cURL** uses that execution's captured URL, method, headers, and body through Bruno's existing HTTPSnippet generator. It does not reread or interpolate the saved request. Select a different loop execution to copy that iteration's request.

The button is implemented in `packages/bruno-app/src/components/FlowTabPanel/NodeExecutionDetails/index.jsx`, with generation in the adjacent `curl.js`. It appears when the execution snapshot includes `input.request.url`; nodes without a captured request do not show it.

Text and JSON payloads are supported. Binary-only, redacted-file, and multipart captures produce an explanation instead of an inaccurate command. Captured authentication headers are included in the copied command.

## Duplicate and lay out nodes

Select nodes, focus the canvas, and press **Ctrl+D** or **Cmd+D**. Each clone receives a unique ID, independent configuration, and a 40-pixel offset. Connections are not duplicated. Execution results and validation badges are cleared from the clones. Keyboard shortcuts do not apply inside inspector controls or text fields. You can also right-click a node and choose **Duplicate Node**.

**Beautify Graph** computes a left-to-right layout with Dagre, using measured node dimensions when available. Positions animate into place, and the viewport fits the result. Save to persist the new positions. Duplication and layout are disabled during execution.

## Assertion snippets

Assert nodes offer **Status 200**, **Response Time < 500ms**, **Content-Type JSON**, and **Body contains ID** chips. Each appends a check while retaining existing assertions. Response duration is in milliseconds.

## Starter templates

The **New Flow** modal offers four templates:

- **Blank Flow:** Start → End.
- **OAuth2 Login & Fetch:** choose login/API requests; a script extracts `access_token` or `token`, then the API step overrides its Authorization header. Adapt token extraction to your response shape.
- **Async Job Polling Loop:** choose submit, status, and result requests. The template waits two seconds between polls, tests `res.body.status !== 'DONE'`, and permits at most 30 polls. Map the returned job ID to your status/result request variables and adapt the completion condition.
- **Data-Driven Batch Run:** choose a processing request and add `data.csv` at the collection root. Each row is available as `ctx.row`, with `ctx.index`; configure your request variables for the row's fields. Each iteration asserts a 200 response before returning to the dataset node.

Templates use requests selected from the collection and save portable references. They provide an editable scaffold; endpoint-specific authentication, data mappings, and response checks still depend on your API.

Missing or removed template selections are guarded during rendering and rejected during validation with “Choose a valid starter template.”

## Per-node request overrides

Select a Request node and open **Overrides**. Add header/query rows or enter a JSON object in **Body overrides**, then choose **Apply body overrides**. Save the flow. These overrides apply to a private copy before normal variable interpolation and request scripts. The referenced request file is preserved.

Applying valid body overrides clears previous errors and shows a success toast. Invalid JSON or a non-object body keeps an inline validation message.

The desktop and CLI share this contract:

```json
{
  "itemRef": "users.bru",
  "overrides": {
    "headers": { "X-Build": "{{buildId}}" },
    "params": { "limit": 10, "status": "active" },
    "body": { "name": "{{userName}}", "settings": { "enabled": true } }
  }
}
```

Headers replace matching request headers without regard to case. An explicit Authorization override replaces configured authentication for that step. Query parameters replace matching keys in the URL, retaining unrelated parameters. Header/query values accept strings, numbers, or booleans. Body fields shallow-merge a JSON object, so nested objects are replaced as a whole. A request with no body can receive a JSON body; array, GraphQL, form, file, and other non-JSON-object bodies are rejected for body merging. Unknown override fields produce an error. Normal request scripts still run and may change the effective request afterward.

Validation checks the override structure. JSON import/export preserves it. AI generation remains conservative and does not generate overrides; you can add them manually after generation.

## Verification

### Review follow-up — November 7, 2026

- Confirmed the existing Copy as cURL button and added a component test that clicks it and verifies the captured payload reaches the clipboard.
- Guarded missing starter templates in both validation and rendering.
- Moved body-override success feedback to `toast.success()` and kept validation errors inline.
- Simplified assertion synchronization to react to saved assertion changes. The inspector's existing `key={id}` resets the editor when switching nodes; updates to the same node still synchronize, and locally typed blank lines remain intact.

After these changes, **33 flow UI tests** and **5 affected Electron E2E scenarios** passed. The E2E checks cover duplication/layout/snippets, the OAuth, polling, and batch templates, and real request overrides followed by Copy as cURL. Lint and `git diff --check` passed. These were targeted follow-up runs; the full-suite and production-build results below are from the preceding Phase 7 verification.

### Phase 7 verification

The complete Electron flow suite passed **21/21** scenarios. New coverage verifies duplication by keyboard and context menu, layout persistence, assertion snippets, all three configured starter templates, and header/query/body overrides followed by copying the captured request as cURL. Existing creation, save/reload, JSON interchange, Bru/YAML persistence, cancellation, debugging, validation, and inspection workflows passed too.

Targeted regression tests passed: **78 backend**, **33 frontend**, and **14 CLI** tests (**125 total**). CLI tests execute actual local HTTP requests and verify environment injection, interpolated overrides, body merging, header replacement, and preservation of saved request files. Frontend tests also verify that browser ResizeObserver layout notifications keep the application mounted while actual JavaScript exceptions still show the error boundary. Lint, syntax checks, `git diff --check`, and the production frontend build passed. The build retains dependency warnings and two unavailable documentation-image downloads. Live AI providers were not exercised.
