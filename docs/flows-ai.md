# Generate a flow with AI

1. Enable AI and configure a provider/model in **Preferences > AI**.
2. Open a flow and choose **Generate Flow** in its toolbar.
3. Describe the workflow, for example: “Log in, fetch inventory, and verify that items are available.” Select a model or use the configured default.
4. Choose **Generate preview**. Review the steps, response mappings, notices, and full graph JSON.
5. Choose **Replace flow with preview** to apply it to the editor. Save and run through the existing flow controls. Existing autosave preferences still apply.

Generating a preview does not modify the current flow, execute requests, or run generated scripts. Closing or cancelling generation discards late results. Replacement is disabled while the flow is executing. Editing the description or model clears the previous preview.

## Collection context and mapping

The generator uses saved HTTP and GraphQL requests in the current collection. It receives request names, collection-relative file references, methods, input variable names, and response shapes. The backend replaces response values with type placeholders before contacting the provider. Request URLs, headers, body values, API keys, and environment variable values are not included in the catalog. The description you enter is sent to the selected provider.

Execute relevant requests before generating a flow to make response shapes available. Generated request nodes refer to existing files; they reuse the collection’s authentication, environments, scripts, and tests.

For linear workflows, omitted edges are connected in node order. The model supplies explicit branch and loop connections. The backend adds response mappings when a downstream input variable uniquely matches a known response field, including common token name variants. For example, a login response’s `token` field can map to a downstream `authToken` input as `authToken: res.body.token`.

Ambiguous fields require manual mapping. Missing response samples produce review notices, and invented paths are rejected when a sample exists. Generation cannot establish that a workflow meets an API’s business rules; inspect the preview and verify it through execution.

## Validation and scope

The backend validates generated JSON, node types, request references, expressions, script syntax, response mappings, and graph connections before returning a preview. It requires one Start node, at least one End, reachable nodes, correct condition/loop handles, and loop bodies that return to their loop node. Scripts are compiled for syntax checks but never executed during generation.

Generation supports Start, Request, Condition, Delay, Script, End, For Each, While, and expression-based Assert nodes. Dataset nodes, sub-flow nodes, and JSON Schema assertion settings remain available through manual editing; AI generation does not discover their files or create those node types. Request overrides are not generated.

Limits: 200 collection requests, 100 generated nodes, 300 connections, a 12,000-character description, and an 8,192-token output budget. Generated loops are bounded and delays are limited to one hour per node. Invalid provider responses are shown as errors and leave the current flow unchanged.

## Implementation and validation

- `packages/bruno-app/src/components/FlowTabPanel/GenerateFlow`: description/model controls, cancellation, preview, and explicit replacement.
- `packages/bruno-electron/src/ipc/ai/flow-generation.js`: catalog redaction, prompts, validation, mapping inference, and `renderer:ai-generate-flow`.
- The existing AI provider resolution and stream cancellation registry handle credentials and cancellation.

```sh
npm run test:ci --workspace=packages/bruno-electron -- --runInBand src/ipc/ai src/services/flow-runner
npm test --workspace=packages/bruno-app -- --runInBand src/utils/ai/index.spec.js src/components/FlowTabPanel/GenerateFlow
npm run build:web
```

Automated generation tests use mocked provider responses. They cover generation-to-execution, validation failures, redaction, provider errors, cancellation, preview/replacement behavior, and existing AI utilities. A real provider call and interactive Electron verification have not been performed.
