# Running flows from the CLI

From a collection root, run a saved `.flow.bru` file (or a `.yml` flow in a YAML collection):

```sh
bru run --flow flows/user-onboarding.flow.bru --env staging
```

In this repository, use `node /path/to/bruno/packages/bruno-cli/bin/bru.js` in place of `bru` when testing the local checkout.

The CLI and desktop share the engine in `packages/bruno-js/src/flow`. All implemented nodes work headlessly: requests, scripts, conditions, delays, loops, datasets, sub-flows, and assertions. Collection requests use the CLI’s existing authentication, folder inheritance, request scripts, assertions, tests, certificate, proxy, and environment handling. Flow context variables are passed into request runtime variables; response mappings populate the context for downstream nodes.

Flow, dataset, sub-flow, and request references are relative to the collection root and must resolve within it. Request references identify existing HTTP or GraphQL collection items. Request nodes support header, query-parameter, and JSON-object body overrides; see [Phase 7](flows-phase7.md). The resolved environment and `--env-var` values are available directly in `ctx` before the first step. Save flow files before running them.

## Reports and CI

```sh
bru run --flow flows/user-onboarding.flow.bru \
  --env staging --env-var baseUrl=https://staging.example.test \
  --reporter-json reports/flow.json \
  --reporter-junit reports/flow.xml
```

Create output directories before running the command. `--output results.json --format json` and `--output results.xml --format junit` also work. HTML reports are not supported for flows.

The terminal prints colored completion/failure lines for each step and a final status with duration. Nested flow progress is indented. Colors follow the CLI’s normal terminal detection.

JSON contains:

- `summary`: final status, duration, context, every executed step (including loop repetitions), and latest results per node. Child summaries appear on sub-flow steps.
- `requests`: request execution results, including request-level assertion and script test results.

JUnit contains a testcase per executed step, including repeated and nested steps. Failed assertions, request tests, execution errors, and cancellation produce failures. A flow that fails before any step executes still produces a failed testcase.

Existing `--reporter-skip-all-headers`, `--reporter-skip-headers`, and request/response body omission options also apply to flow reports. Context variables remain part of the JSON summary.

Exit status:

| Code | Meaning |
| --- | --- |
| `0` | Flow succeeded |
| `1` | Flow, request, test, or assertion failed |
| `130` | Execution cancelled |
| Other nonzero | CLI setup, argument, or report writing error |

Ctrl+C and SIGTERM cancel the flow and active requests. Configured reports are written before exit. The flow’s error policy applies; `--bail` forces stopping on node errors. Assertion nodes configured to collect failures still collect them. Request sequencing comes from graph connections; `setNextRequest` does not change graph routing. A request script calling `bru.runner.stopExecution()` cancels the flow.

Do not combine `--flow` with request/folder positional paths, `-r`, tag filters, or `--tests-only`. Ordinary request/folder runs skip flow files; flow execution is explicit. Use Delay nodes for graph pacing.

## Validation

```sh
npm run test:ci --workspace=packages/bruno-cli -- --runInBand tests/flows
npm run test:ci --workspace=packages/bruno-electron -- --runInBand src/services/flow-runner
```

The command integration tests use a local HTTP server and subprocesses. They verify environment overrides, context interpolation, output extraction, request tests, JSON/JUnit files, exit status, and ordinary recursive request execution.
