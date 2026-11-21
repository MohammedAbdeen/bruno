# Flow smoke test

This self-contained example needs no HTTP server, credentials, environment or saved requests.

Start → for-each over `[10, 20, 30]` → add each number → condition → assertions → End.
The loop returns to its loop node after each addition. The false condition branch deliberately fails.

Expected result: **success**, `total = 60`, `processed = 3`, both assertions pass.

## Desktop JSON import

Right-click a collection and select **Import Flow (JSON)**, then choose `flow-smoke-test.json`.
Open the imported flow and click Run.

## IntelliJ

In plugin 0.4.6 or later, choose **Bruno → Flows → Import JSON…**, select `flow-smoke-test.json`,
and choose your destination collection folder. The imported flow is selected automatically.
Leave Env blank and click **Run Flow**. Configure **Runner settings…** with this fork's CLI/Node paths if necessary.

Alternatively, copy the ready-made `collection/flows/smoke-test.flow.bru` into an existing collection's `flows` folder and Refresh.

## CLI

From this example's `collection` directory, run this fork's CLI:

```sh
node /absolute/path/to/bruno/packages/bruno-cli/bin/bru.js run --flow flows/smoke-test.flow.bru
```

To try the failure branch, change one of the starting numbers so the sum is no longer 60.
JSON and Bru copies are separate examples: edit the copy you are running.
