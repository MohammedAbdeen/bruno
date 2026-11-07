import React, { useState } from 'react';
import useCopyToClipboard from 'hooks/useCopyToClipboard';
import { capturedRequestToCurl } from './curl';

const formatSnapshot = (snapshot) => {
  const ancestors = [];
  return JSON.stringify(snapshot, function (_key, value) {
    if (typeof value === 'bigint') return `${value}n`;
    if (value && typeof value === 'object') {
      while (ancestors.length && ancestors[ancestors.length - 1] !== this) ancestors.pop();
      if (ancestors.includes(value)) return '[Circular]';
      ancestors.push(value);
    }
    return value;
  }, 2);
};

const NodeExecutionDetails = ({ executions = [] }) => {
  const [selectedId, setSelectedId] = useState('');
  const { copyToClipboard } = useCopyToClipboard();
  const [copyMessage, setCopyMessage] = useState('');
  const selected = executions.find((entry, index) => (entry.stepId || String(index)) === selectedId) || executions[executions.length - 1];
  if (!selected) return <p className="text-xs text-zinc-500">Run the flow to inspect this node’s input and output.</p>;
  const input = selected.result?.input || selected.input;
  const output = selected.result?.output || selected.output;
  const currentIndex = executions.indexOf(selected);
  return (
    <div className="space-y-3 text-xs select-text" data-testid="node-execution-details">
      {executions.length > 1 && (
        <label className="block">Execution
          <select aria-label="Node execution" className="w-full p-2 border rounded bg-white dark:bg-zinc-900" value={selected.stepId || String(currentIndex)} onChange={(event) => setSelectedId(event.target.value)}>
            {executions.map((entry, index) => <option key={entry.stepId || index} value={entry.stepId || String(index)}>Execution {index + 1} — {entry.status}</option>)}
          </select>
        </label>
      )}
      <p className="text-zinc-500">{selected.status} · {selected.duration || 0}ms</p>
      {input?.request?.url && (
        <div className="space-y-1">
          <p className="break-all font-mono">{input.request.method} {input.request.url}</p>
          <button
            className="px-2 py-1 border rounded"
            onClick={async () => {
              try {
                await copyToClipboard(capturedRequestToCurl(input.request)); setCopyMessage('cURL copied');
              } catch (error) { setCopyMessage(error.message || 'Could not copy cURL'); }
            }}
          >Copy as cURL
          </button>
          {copyMessage && <p role="status">{copyMessage}</p>}
        </div>
      )}
      <section>
        <h3 className="font-semibold mb-1">Input</h3>
        <p className="text-zinc-500 mb-1">Context, previous response, node settings, and request details when available.</p>
        <pre data-testid="node-input" className="p-2 border rounded bg-zinc-50 dark:bg-zinc-900 overflow-auto max-h-64">{formatSnapshot(input || {})}</pre>
      </section>
      <section>
        <h3 className="font-semibold mb-1">Output</h3>
        {output ? <pre data-testid="node-output" className="p-2 border rounded bg-zinc-50 dark:bg-zinc-900 overflow-auto max-h-64">{formatSnapshot(output)}</pre>
          : <p className="text-zinc-500">{selected.status === 'running' ? 'Running — output appears when the step finishes.' : 'No output recorded.'}</p>}
      </section>
      {(selected.error || selected.result?.error) && <p role="alert" className="text-rose-500">{selected.error || selected.result.error}</p>}
    </div>
  );
};
export default NodeExecutionDetails;
