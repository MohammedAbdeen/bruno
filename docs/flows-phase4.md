# Advanced flows

Add these nodes from the flow toolbar. Configure a node by selecting it, save the flow, then run it in the desktop app.

## Loops and datasets

`for-each`, `while`, and `data-source` have **Body** and **Done** output ports:

1. Connect Body to the first step to repeat.
2. Connect the last step of the body back to the loop node’s input.
3. Connect Done to the step after the loop.

A body that terminates without returning to its loop is reported as a failed run. Empty arrays take Done immediately. Nested loops are supported; use different item/index variable names when both levels need their values.

- **For Each:** evaluates an array expression, for example `ctx.users`. The current item is available as `ctx.item` and its zero-based position as `ctx.index` (both names are configurable). The input array is evaluated once per loop entry.
- **While:** evaluates its expression before each iteration using the latest `ctx` and `res`. For polling, use an expression such as `!res.body?.ready`, connect Body to a request followed by a Delay node, and connect Delay back to While.
- **Dataset:** reads a collection-relative file such as `data/users.json` or `data/users.csv`, then runs Body once per record. JSON must contain an array. CSV uses the first row as unique, nonempty column names, supports quoted multiline fields and escaped quotes, and produces string values. Current records default to `ctx.row`.

Maximum iterations default to 1,000; While nodes created by the toolbar default to 100. Exceeding the limit fails the node. The runner also limits each flow invocation to 10,000 executed steps (`flow.config.maxSteps` can override this). Scripts and expressions have a one-second synchronous evaluation limit. Stop cancels execution, including delays and active HTTP requests.

## Sub-flows

Set the collection-relative path to a saved flow, for example `flows/login.flow.bru`. The child receives a copy of the parent context; on success its final context replaces the parent context. Child Start defaults apply only to variables absent from the inherited context. The child response object does not replace the parent’s last response: return values through `ctx`.

Child failures propagate to the parent node. Nested calls share cancellation and are limited to 16 levels. Dataset and sub-flow references must resolve inside the collection, including through symlinks. Paths are relative to the collection root, including in nested sub-flows. Save child flows before running the parent.

## Assertions

Enter one JavaScript expression per line, using `ctx` and the latest normalized `res`, for example:

```js
res.status === 200
res.body.users.length > 0
ctx.token !== undefined
```

Optionally supply a JSON Schema for the response body. Schema and expression results are recorded on the assertion step. Clear all expressions to validate only the schema.

**Stop on first failure** stops evaluation and, by default, the flow. Uncheck it to evaluate all checks and continue downstream; the final run still reports failure. `flow.config.stopOnError: false` also permits continuing past failed nodes. Invalid expressions and schemas fail the assertion.

Run summaries retain each iteration in `steps`; `nodeResults` contains the latest result per node. The desktop timeline shows repeated steps and assertion pass counts. Child details are available in the parent node’s `subFlow` result; child telemetry is wrapped as `flow:subflow-event` so it cannot reset the parent’s timeline.

## Validation

```sh
npm run test:ci --workspace=packages/bruno-electron -- --runInBand src/services/flow-runner
npm test --workspace=packages/bruno-lang -- --runInBand v2/tests/flow.spec.js
npm test --workspace=packages/bruno-filestore -- --runInBand src/formats/bru/tests/flow.spec.js
npm run build:web
```

CLI execution is implemented in Phase 5; see [CLI flows](flows-cli.md). AI flow generation is implemented in Phase 6; see [AI flows](flows-ai.md). Interactive desktop testing is separate from the automated engine, persistence, and frontend build checks.
