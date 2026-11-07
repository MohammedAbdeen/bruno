# Import and export flows as JSON

**Export:** open a flow and choose **Export JSON** in the toolbar, then select where to save the `.json` file. This includes current unsaved graph edits.

**Import:** right-click a collection or folder and choose **Import Flow (JSON)**, or choose **Import JSON** in an open flow’s toolbar. Select a JSON file. Import creates and opens a new flow in the collection (or selected folder); it does not replace the current flow. Existing filenames are handled by the normal unique-file creation path.

Imported flows use the destination collection’s native format: `.bru` or `.yml`. Both formats support saving and reopening flow graphs.

Nodes, connections, positions, variable mappings, flow configuration, and the viewport are included. Selection state, execution status, and captured node responses are omitted. Local absolute request/sub-flow/dataset references are converted to collection-relative paths. Referenced request, sub-flow, and dataset files are not bundled; those files must be present in the destination collection for execution.

The exported format is:

```json
{
  "type": "bruno-flow",
  "version": 1,
  "name": "Example",
  "flow": {
    "nodes": [{ "id": "start", "type": "start", "position": { "x": 100, "y": 100 }, "data": {} }],
    "edges": [],
    "viewport": { "x": 0, "y": 0, "zoom": 1 },
    "config": { "stopOnError": true }
  }
}
```

Raw graph JSON containing `nodes` and `edges` arrays is also accepted and named “Imported Flow.” Incomplete graphs can be transferred for further editing. Import validates the graph schema, node/edge IDs, connection targets, file references, and format version; it does not execute scripts or requests. Invalid files leave existing flows unchanged. Files larger than 10 MB are rejected. Cancelling a file dialog makes no changes.

Validation commands:

```sh
npm run test:ci --workspace=packages/bruno-electron -- --runInBand src/services/flow-json
npx playwright test tests/flows/flows.spec.ts --grep 'exports JSON' --project=default --workers=1 --reporter=line
```

Verified with 7 format/file-dialog unit tests and an Electron test that exports, imports, and executes the imported flow.

The provided [Sum Example](../examples/flows/sum-flow.json) is covered by Electron regression tests in both Bru and YAML collections: import, restart, reopen, execute, and assert `total === 60`. After updating file-storage code, restart the development app so its main process and parser workers load the rebuilt package.
