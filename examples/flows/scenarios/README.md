# Flow JSON scenario collection

Complete examples of every currently supported flow node: `start`, `end`, `script`, `request`, `condition`, `assert`, `delay`, `for-each`, `while`, `data-source`, and `sub-flow`. Each numbered `.json` file is an importable version 1 flow, with node positions and connections included. Examples illustrate supported scenarios rather than every possible graph combination.

## Import and run

1. Use the desktop app, CLI, or IntelliJ plugin from this fork; the upstream published CLI may not include flows.
2. Open this folder as a Bruno collection. To use another collection, copy `requests/`, `data/`, and `flows/child.flow.bru` into that collection, preserving their paths.
3. Desktop: right-click the collection/folder → **Import Flow (JSON)**. IntelliJ: **Bruno → Flows → Import JSON…**, then select this folder as the destination collection. Select a numbered JSON file; importing never sends a request.
4. Open the created native `.flow.bru` file and run it. A YAML destination collection creates a YAML flow; adapt request and child-flow references to the native files in that collection. The supplied resource files target a Bru collection.

The JSON wrapper is `{"type":"bruno-flow","version":1,"name":"…","flow":{…}}`. Its `flow` contains `nodes`, `edges`, `viewport`, and `config`. Raw graphs are also accepted, but the wrapper preserves the flow name. Names/IDs must be valid and edge endpoints must exist. JSON import does not bundle dependencies, environments, secrets, or execution history.

Start the loopback demo API in another terminal from the repository root:

```sh
node examples/flows/scenarios/mock-server.cjs
```

Set `baseUrl=http://127.0.0.1:4010` in a collection environment, or supply it with CLI `--env-var`. IntelliJ's global API environment does not automatically configure the external flow CLI; select a **collection environment** in the Flows Env field. The IntelliJ runner needs this fork's `packages/bruno-cli/bin/bru.js` (or its checkout) and Node configured if `bru` is unavailable on PATH.

After import, run from the collection root using the actual created filename:

```sh
node /path/to/bruno/packages/bruno-cli/bin/bru.js run \
  --flow 'flows/Request and Response Schema.flow.bru' \
  --env-var baseUrl=http://127.0.0.1:4010 --env-var buildId=9421
```

## Scenario catalog and expected results

| File | Path through the graph | Expected result |
| --- | --- | --- |
| [01-blank.json](01-blank.json) | Start → End | Success, 2 steps; no server needed |
| [02-variables-and-script.json](02-variables-and-script.json) | Start → Script → Assert → End | `total=60`, `message="Build 9421"`; no server needed |
| [03-request-and-schema.json](03-request-and-schema.json) | Start → Request → Assert → End | GET 200, `echoMethod="GET"`, `scriptMarker="from-request-script"`, response schema passes |
| [04-login-and-authorized-request.json](04-login-and-authorized-request.json) | Start → Login → Authenticated request → Assert → End | `token="local-demo-token"`, echoed Authorization is `Bearer local-demo-token` |
| [05-conditional-branches.json](05-conditional-branches.json) | Start → Condition → Primary or Fallback → Assert → End | `route="primary"`; change `start.data.initialVars.usePrimary` to `false` for `route="fallback"` |
| [06-for-each.json](06-for-each.json) | Start → Loop ↔ Add → Assert → End | `total=60`, `seen=[0,1,2]`; no server needed |
| [07-polling-with-delay.json](07-polling-with-delay.json) | Start → While ↔ Delay → Request → Advance → Assert → End | Polls 3 times, `jobStatus="DONE"`, `attempt=3` |
| [08-dataset-csv.json](08-dataset-csv.json) | Start → CSV loop ↔ Collect → Assert → End | `names=["Alice","Bob"]`, `total=30`; no server needed |
| [09-dataset-json.json](09-dataset-json.json) | Start → JSON loop ↔ Collect → Assert → End | Same result using typed JSON rows; no server needed |
| [10-sub-flow.json](10-sub-flow.json) | Start → Child → Assert → End | Parent provides 21; child returns `childValue=42`; no server needed |
| [10-child.json](10-child.json) | Start → Script → End | Standalone import of the same child graph, also yields 42 |
| [11-request-overrides.json](11-request-overrides.json) | Start → POST → Assert → End | Header `X-Flow=flow-demo`, query `page=2`, body name replaced, `keep=1` preserved |
| [12-continue-on-error.json](12-continue-on-error.json) | Start → Failed script → Cleanup → Assert → End | **Expected failure**, but `cleanedUp=true` and End executes |
| [13-collect-assertion-failures.json](13-collect-assertion-failures.json) | Start → Failed checks → Cleanup → End | **Expected failure**, both checks recorded, `cleanedUp=true` |
| [14-stop-on-error.json](14-stop-on-error.json) | Start → Failed assertion; stop | **Expected failure**, only first check evaluated; downstream script and End are skipped |
| [15-loop-limit.json](15-loop-limit.json) | Start → While ↔ Tick; stop at limit | **Expected failure**, `ticks=2`, error `Loop iteration limit exceeded` |

The mock login supplies a demo token for testing extraction and interpolation, not a real OAuth2 exchange. For your API, replace the login request and token mapping. Cookie-backed CSRF authentication also needs the session cookie associated with the fetched token; copying only a token header is insufficient. The automated examples disable cookie persistence so they do not modify your existing CLI cookie jar.

## Variables, expressions, and request reuse

Initial values are applied in this order, with later sources taking precedence:

1. `flow.config.initialVars`
2. Start node `data.initialVars`
3. Runner-provided context, including resolved collection environment/CLI `--env-var` values

Use JavaScript `ctx.name` in scripts/conditions/assertions and `{{name}}` in request URLs, headers, parameters, and bodies. CLI environment overrides are strings; convert with `Number(ctx.value)` when needed. Scripts can mutate `ctx`; request scripts can use `bru.setVar`. Request `outputVars` mappings are applied after request-script runtime updates and therefore take precedence. `res` represents the most recent request response (`status`, `body`/`data`, `headers`, `duration`).

For example, [03](03-request-and-schema.json) uses:

```json
{
  "itemRef": "requests/echo.bru",
  "outputVars": {
    "echoMethod": "res.body.method",
    "savedStatus": "res.status"
  }
}
```

This is a request node's `data` fragment, not a standalone import. `outputVars` values are response property paths, not JavaScript expressions or a full JSONPath query language. Use a following Script node for calculations. Conditions/assertions accept expressions with trailing semicolons and statement lists; scripts use `data.code`. Blank assertion lines are ignored; an assertion node still needs an actual check or a schema.

Overrides in [11](11-request-overrides.json) replace matching headers/query parameters on a private copy of the saved request. JSON-object bodies **shallow-merge**: `nested: {"new":true}` replaces the entire original `nested` object, while unrelated top-level `keep` survives. Body overrides require a JSON object or an empty body, not multipart/binary content. The saved request stays unchanged.

## Branches, loops, datasets, and sub-flows

Conditions route on `sourceHandle: "true"` or `"false"`. Each branch in [05](05-conditional-branches.json) connects to the same assertion. Execution follows one route; this is not parallel fan-out or a parallel join. A missing matching condition edge ends that route.

Every loop uses `body` and `done` handles and a body edge returning to the loop node. For example, [06](06-for-each.json) includes:

```json
[
  { "id": "loop-add-body", "source": "loop", "target": "add", "sourceHandle": "body" },
  { "id": "add-loop", "source": "add", "target": "loop" },
  { "id": "loop-verify-done", "source": "loop", "target": "verify", "sourceHandle": "done" }
]
```

This is an `edges` fragment; the file includes the nodes too. An empty array takes `done` without executing the body. `itemVar`/`indexVar` name context variables; preserve values you need after the loop in your own accumulator. Nested loops should use different item/index names. Loops default to a maximum of 1,000 iterations; choose an explicit positive integer `maxIterations`. `config.maxSteps` defaults to 10,000 executed steps and also bounds repeated loop visits.

Polling example [07](07-polling-with-delay.json) waits **20 ms** per iteration to keep the demo fast. Use `durationMs: 2000` for a real two-second poll. Update its condition/output mapping to match your API and retain the iteration limit.

Dataset files are collection-relative. JSON datasets must be arrays. CSV requires unique, nonempty headers and matching columns; row values are strings, so [08](08-dataset-csv.json) converts amounts explicitly. Both data-source examples accumulate results without making HTTP requests; insert a Request node in the body to process each `{{row.name}}` against your API.

Sub-flow [10](10-sub-flow.json) references the supplied native [flows/child.flow.bru](flows/child.flow.bru), not the interchange JSON. Parent context is supplied to the child; successful child context is returned to the parent. Importing [10-child.json](10-child.json) separately is optional because the native child is already included. Sub-flow nesting beyond depth 16 fails. References must remain within the collection directory.

## Failures, debugging, and execution output

`config.stopOnError` defaults to `true`. Setting it to `false` continues along graph connections after a failed node, but **does not turn the final result into success**. Assertion `failFast: false` collects all checks and allows downstream execution even when the flow's stop-on-error default is true. See examples [12](12-continue-on-error.json), [13](13-collect-assertion-failures.json), and [14](14-stop-on-error.json) to compare these behaviors. This is not automatic retry or exception-handler routing.

[02](02-variables-and-script.json) includes `data.breakpoint: true` on its script. Use **Debug Flow** in the desktop app to pause and inspect/edit context, step, or continue. Ordinary CLI runs ignore breakpoint pauses. Stop/Ctrl+C cancels execution; cancellation is a runner action rather than an import setting.

The desktop node inspector and IntelliJ flow execution history expose per-step input/output. For persistent CLI reports, create a directory first and pass JSON/JUnit destinations:

```sh
mkdir -p reports
node /path/to/bruno/packages/bruno-cli/bin/bru.js run \
  --flow 'flows/Request and Response Schema.flow.bru' \
  --env-var baseUrl=http://127.0.0.1:4010 \
  --reporter-json reports/flow.json --reporter-junit reports/flow.xml
```

JSON `summary.steps` preserves each repeated step's input context/request and output context/response. `nodeResults` contains the latest result per node. Sub-flow results appear on their parent step. Treat reports as sensitive: context and captured responses can contain credentials. Successful flows exit 0, failures exit 1, cancellation exits 130. Examples 12–15 should exit 1.

## Verify the examples

With this checkout's dependencies installed, run from the repository root:

```sh
node examples/flows/scenarios/verify.cjs
```

The verifier imports every numbered JSON through the desktop normalization/schema path, serializes native Bru files into a temporary collection, and executes them through the actual CLI against a loopback server on a free port. It verifies both condition branches, request interpolation/overrides, schema checks, request-script propagation, dataset/sub-flow resources, expected failures, cleanup, iteration limits, step I/O, and JSON/JUnit report generation. It removes its temporary collection afterward and does not change your imported files or require the demo server to be running.
