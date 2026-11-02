import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconCode } from '@tabler/icons';

const ScriptNode = ({ data, selected }) => {
  const { code = '', runStatus } = data || {};

  let borderClass = selected ? 'border-indigo-500 shadow-indigo-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700';
  if (runStatus === 'running') {
    borderClass = 'border-teal-500 ring-2 ring-teal-500/30 animate-pulse';
  } else if (runStatus === 'success') {
    borderClass = 'border-emerald-500';
  }

  return (
    <div
      className={`px-4 py-3 shadow-md rounded-lg bg-white dark:bg-zinc-800 border-2 transition-all min-w-[200px] max-w-[260px] ${borderClass}`}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="w-3 h-3 bg-zinc-400 dark:bg-zinc-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />

      <div className="flex items-center space-x-2 mb-2">
        <div className="p-1 rounded bg-teal-500/10 text-teal-500">
          <IconCode size={14} />
        </div>
        <span className="text-xs font-semibold uppercase text-zinc-700 dark:text-zinc-300">
          Script
        </span>
      </div>

      <div className="p-1.5 rounded bg-zinc-50 dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700">
        <code className="text-xs font-mono text-zinc-800 dark:text-zinc-200 break-all line-clamp-2">
          {code || '// script code...'}
        </code>
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="w-3 h-3 bg-teal-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
    </div>
  );
};

export default memo(ScriptNode);
