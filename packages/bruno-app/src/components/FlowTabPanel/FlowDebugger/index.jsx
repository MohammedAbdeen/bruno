import React, { useState } from 'react';

const FlowDebugger = ({ paused, onResume }) => {
  const [text, setText] = useState(() => {
    try { return JSON.stringify(paused.context, null, 2); } catch { return ''; }
  });
  const [error, setError] = useState('');
  const [isResuming, setIsResuming] = useState(false);
  const resume = async (action) => {
    if (isResuming) return;
    setIsResuming(true);
    setError('');
    try {
      const context = text.trim() ? JSON.parse(text) : undefined;
      if (context !== undefined && (!context || typeof context !== 'object' || Array.isArray(context))) throw new Error('Context must be a JSON object');
      await onResume(action, context);
    } catch (err) { setError(err.message); } finally { setIsResuming(false); }
  };
  return (
    <section role="region" aria-label="Flow debugger" className="p-3 border-b bg-amber-50 dark:bg-zinc-800 text-xs space-y-2">
      <strong>Paused before {paused.nodeId} ({paused.flowName})</strong>
      <label className="block">Paused context (editable JSON)
        <textarea aria-label="Paused context" value={text} rows={4} onChange={(event) => setText(event.target.value)} className="w-full p-2 border rounded font-mono bg-white dark:bg-zinc-900" />
      </label>
      {error && <p role="alert" className="text-rose-500">{error}</p>}
      <div className="flex gap-2">
        <button disabled={isResuming} onClick={() => resume('step')} className="px-3 py-1 border rounded">Step Next</button>
        <button disabled={isResuming} onClick={() => resume('continue')} className="px-3 py-1 border rounded">Continue</button>
      </div>
    </section>
  );
};
export default FlowDebugger;
