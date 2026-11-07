# Delete nodes and inspect execution data

## Delete a node

Select a node, then choose **Delete node** at the bottom of its inspector. Its incoming and outgoing connections are removed too. Save the flow to persist the deletion.

You can also focus the canvas and press **Delete** or **Backspace**. These keys edit text normally when a node configuration input has focus. Deletion is disabled during execution. If you remove the Start node, add a new one with **Add Node → Start**.

## Inspect input and output

Run a flow, then select a node. The inspector’s **Input / Output** tab shows:

- **Input:** the incoming context, previous response, node parameters, and actual request details when supplied by the request runner.
- **Output:** the resulting context, response, and execution details such as condition decisions, loop routing, assertion results, duration, and errors.

Snapshots are captured independently for each execution. Later steps cannot change earlier snapshots. For loops, use the **Execution** selector to compare individual iterations. The newest execution is shown by default.

During an active step, input is available immediately; output appears when it finishes. Failed steps retain their input and partial output, including the error. Nodes that have not run show a prompt to run the flow.

The execution timeline also offers **View input / output** for each recorded step. This lets you inspect previous steps without selecting their canvas node.

Request executions with a captured request URL also show **Copy as cURL** beside the method and URL. It copies the selected execution's captured request, including interpolated headers and body. Binary-only, redacted-file, and multipart captures show an explanation when the original request cannot be reconstructed. The button lives in `FlowTabPanel/NodeExecutionDetails/index.jsx`; see [Phase 7](flows-phase7.md#copy-a-captured-request) for details.

CLI JSON reports include these snapshots. Existing request/response header and body omission flags apply to captured transport fields as well; flow context values remain in the report.

Restart the development app after updating the engine so the main process loads the new execution events:

```sh
npm run dev
```


Validation: the complete 12-test Electron flow suite passed, including persistent deletion, keyboard/text-input behavior, and per-iteration input/output inspection. A focused keyboard test also passed after refining selection handling. Engine snapshot, inspection UI, and CLI report-omission regression tests passed; the final changed UI files passed lint.
