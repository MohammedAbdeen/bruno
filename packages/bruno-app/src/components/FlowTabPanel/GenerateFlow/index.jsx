import React, { useState, useEffect, useRef } from 'react';
import { getAiStatus, aiGenerateFlow, stopAiGeneration } from 'utils/ai';
import { buildFlowRequests } from './context';

const GenerateFlow = ({ collection, onApply, onClose, defaultModel, isRunning }) => {
  const [prompt, setPrompt] = useState('');
  const [models, setModels] = useState([]);
  const [model, setModel] = useState(defaultModel || '');
  const [statusReady, setStatusReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [candidate, setCandidate] = useState(null);
  const activeId = useRef(null);
  useEffect(() => {
    let mounted = true;
    getAiStatus().then((status) => {
      if (!mounted) return;
      const available = status.availableModels || [];
      setModels(available);
      if (!available.some((entry) => entry.id === defaultModel)) setModel('');
      if (!status.enabled || !available.length) setError('Configure a provider in Preferences > AI.');
      setStatusReady(Boolean(status.enabled && available.length));
    }).catch((err) => { if (mounted) setError(err.message || 'Could not load AI providers'); });
    return () => {
      mounted = false;
      if (activeId.current) stopAiGeneration(activeId.current);
      activeId.current = null;
    };
  }, [defaultModel]);

  const cancel = () => {
    if (activeId.current) stopAiGeneration(activeId.current);
    activeId.current = null;
    setLoading(false);
  };
  const generate = async () => {
    const id = crypto.randomUUID();
    activeId.current = id;
    setLoading(true);
    setError('');
    setCandidate(null);
    try {
      const result = await aiGenerateFlow({ prompt, model: model || undefined, streamId: id,
        collectionPath: collection.pathname, requests: buildFlowRequests(collection) });
      if (activeId.current !== id || result.stopped) return;
      if (result.error) setError(result.error);
      else if (result.flow) setCandidate(result);
      else setError('No flow was generated. Try describing the steps in more detail.');
    } catch (err) {
      if (activeId.current === id) setError(err.message || 'Flow generation failed');
    } finally {
      if (activeId.current === id) {
        activeId.current = null; setLoading(false);
      }
    }
  };
  return (
    <div className="absolute inset-0 z-30 bg-black/30 flex items-center justify-center p-4">
      <div role="dialog" aria-modal="true" aria-label="Generate Flow" className="bg-white dark:bg-zinc-800 rounded-lg shadow-xl p-5 w-full max-w-2xl max-h-full overflow-auto space-y-4">
        <div className="flex justify-between items-center">
          <h2 className="font-semibold">Generate Flow</h2>
          <button
            onClick={() => {
              cancel(); onClose();
            }}
            aria-label="Close"
          >✕
          </button>
        </div>
        <p className="text-xs">Describe a workflow using requests in this collection. Your AI provider receives request names, variable names, and response shapes. Preview the generated steps before replacing this flow.</p>
        <label className="block text-sm">Flow description
          <textarea
            rows={3}
            maxLength={12000}
            value={prompt}
            disabled={loading}
            placeholder="Log in, fetch inventory, then verify that items are available"
            className="w-full border rounded p-2 bg-transparent"
            onChange={(event) => {
              setPrompt(event.target.value); setCandidate(null);
            }}
          />
        </label>
        <label className="block text-sm">Model
          <select
            value={model}
            disabled={loading}
            className="w-full border rounded p-2 bg-white dark:bg-zinc-900"
            onChange={(event) => {
              setModel(event.target.value); setCandidate(null);
            }}
          >
            <option value="">Default model</option>
            {models.map((entry) => <option key={entry.id} value={entry.id}>{entry.name || entry.label || entry.id}</option>)}
          </select>
        </label>
        {error && <p role="alert" className="text-rose-500 text-sm">{error}</p>}
        {loading ? <button onClick={cancel}>Cancel generation</button> : (
          <button disabled={!prompt.trim() || !statusReady} onClick={generate} className="px-3 py-2 rounded bg-indigo-600 text-white disabled:opacity-40">Generate preview</button>
        )}
        {candidate && (
          <div className="space-y-3 border-t pt-3 text-sm">
            <p>{candidate.flow.nodes.length} nodes, {candidate.flow.edges.length} connections</p>
            <ol className="list-decimal pl-5 space-y-1">
              {candidate.flow.nodes.map((node) => (
                <li key={node.id}>
                  <strong>{node.type}</strong> — {node.data?.itemRef || node.data?.expression || node.id}
                  {Object.entries(node.data?.outputVars || {}).map(([name, source]) => <div key={name} className="text-xs font-mono">ctx.{name} ← {source}</div>)}
                </li>
              ))}
            </ol>
            {candidate.notices?.map((notice) => <p key={notice} className="text-amber-600">{notice}</p>)}
            <details><summary>Inspect generated graph</summary><pre className="text-xs overflow-auto max-h-48">{JSON.stringify(candidate.flow, null, 2)}</pre></details>
            <button
              disabled={isRunning}
              onClick={() => {
                onApply(candidate.flow); onClose();
              }}
              className="px-3 py-2 rounded bg-indigo-600 text-white disabled:opacity-40"
            >Replace flow with preview
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
export default GenerateFlow;
