# Desktop flow validation

The follow-up to the six implementation phases checks the complete Electron workflow through Playwright:

- Create a flow from the collection menu and verify the file exists.
- Open a saved flow from the sidebar, edit a script, save it, restart the application, run it, and inspect its output context.
- Run a long Delay node, stop it, and verify the cancelled result.
- Export a flow as JSON, import it as a separate flow, and execute the imported copy.
- Run datasets, nested flows, for-each/while loops, a local HTTP request, output extraction, and assertions together.
- Reject invalid JSON and cancel imports without modifying existing flows.
- Import a flow into a folder through its context menu.
- Import the supplied Sum Example into Bru and YAML collections, restart, and verify its output.
- Delete a connected node, verify connection cleanup, save, and reopen it.
- Delete nodes with keyboard shortcuts while preserving normal text editing.
- Inspect separate input/output snapshots for repeated loop executions.

Each test launches its own Electron instance and uses temporary collection and user-data directories. The tests use local flows and the existing local test HTTP server. They do not call AI providers or external APIs.

```sh
npx playwright test tests/flows/flows.spec.ts --project=default --workers=1 --reporter=line
```

The existing Playwright configuration starts the local frontend and test servers. Electron requires a graphical session (or a virtual display in CI).

Regression fixes from this validation include displaying flow items in the virtual sidebar, including them in sidebar ordering/navigation, and opening flow items as flow tabs, preserving the graph in the backend collection tree, preserving it when merging collection data loaded from disk, retaining saved draft edits, and preventing layout measurements from dispatching Redux updates during rendering.

Live AI-provider generation remains a separate manual check.

Review follow-up: the expanded **16-test** Electron flow suite passed after adding inspector shortcut protection, portable references, expression autocomplete, animated loop-return styling, flow validation, and interactive debugging. The follow-up also passed 76 backend, 22 frontend, and 12 CLI tests. See [Flow review and improvements](flows-review-improvements.md) for behavior and limitations.

Validation result: all 12 Electron tests passed in a complete suite run. A focused keyboard-deletion rerun passed after refining new-node selection. Related regression suites also passed: 229 backend tests, 64 frontend tests, and 32 CLI tests (325 regression tests total). The expanded end-to-end test file passed ESLint, and `git diff --check` passed.

The regression commands used were:

```sh
npm run test:ci --workspace=packages/bruno-electron -- --runInBand src/ipc/ai src/services/flow-runner src/services/flow-json src/services/mount/tree-builder.spec.js
npm test --workspace=packages/bruno-app -- --runInBand src/components/FlowTabPanel/GenerateFlow src/utils/ai/index.spec.js src/utils/collections/flattenSidebarTree.spec.js src/providers/ReduxStore/slices/collections/flow-tree.spec.js
npm run test:ci --workspace=packages/bruno-cli -- --runInBand tests/flows tests/reporters tests/utils/collection/get-call-stack.spec.js
```
