import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

const labels = { 'for-each': 'For Each', 'while': 'While', 'sub-flow': 'Sub-flow', 'data-source': 'Dataset', 'assert': 'Assert' };
const AdvancedNode = ({ type, data = {}, selected }) => {
  const loop = ['for-each', 'while', 'data-source'].includes(type);
  const border = data.runStatus === 'failed' ? 'border-rose-500'
    : data.runStatus === 'success' ? 'border-emerald-500'
      : selected ? 'border-indigo-500' : 'border-zinc-400';
  return (
    <div className={`px-4 py-3 rounded-lg border-2 shadow-md bg-white dark:bg-zinc-800 min-w-[200px] max-w-[260px] ${border}`}>
      <Handle type="target" position={Position.Left} />
      <div className="text-xs font-semibold uppercase mb-2">{labels[type]}</div>
      <code className="text-xs break-all">{data.expression || data.flowRef || data.fileRef || 'Configure node'}</code>
      {data.runStatus === 'running' && <div className="text-xs animate-pulse">Running…</div>}
      {loop ? (
        <>
          <div className="flex justify-between text-xs mt-3"><span>Done ↓</span><span>Body →</span></div>
          <Handle type="source" id="body" position={Position.Right} />
          <Handle type="source" id="done" position={Position.Bottom} />
        </>
      ) : <Handle type="source" position={Position.Right} />}
    </div>
  );
};
export default memo(AdvancedNode);
