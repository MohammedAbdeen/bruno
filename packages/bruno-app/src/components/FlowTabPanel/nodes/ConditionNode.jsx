import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconGitBranch } from '@tabler/icons';

const ConditionNode = ({ data, selected }) => {
  const { expression = 'res.status === 200', runStatus, conditionMet } = data || {};

  let borderClass = selected ? 'border-amber-500 shadow-amber-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700';
  if (runStatus === 'running') {
    borderClass = 'border-amber-500 ring-2 ring-amber-500/30 animate-pulse';
  } else if (runStatus === 'success') {
    borderClass = conditionMet ? 'border-emerald-500' : 'border-rose-500';
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
        <div className="p-1 rounded bg-amber-500/10 text-amber-500">
          <IconGitBranch size={14} />
        </div>
        <span className="text-xs font-semibold uppercase text-zinc-700 dark:text-zinc-300">
          Condition
        </span>
      </div>

      <div className="p-1.5 rounded bg-zinc-50 dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700">
        <code className="text-xs font-mono text-zinc-800 dark:text-zinc-200 break-all line-clamp-2">
          {expression}
        </code>
      </div>

      <div className="flex items-center justify-between mt-3 text-[10px] font-bold">
        <span className="text-rose-500 flex items-center">
          False ↓
        </span>
        <span className="text-emerald-500 flex items-center">
          True →
        </span>
      </div>

      <Handle
        type="source"
        id="true"
        position={Position.Right}
        className="w-3 h-3 bg-emerald-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
      <Handle
        type="source"
        id="false"
        position={Position.Bottom}
        className="w-3 h-3 bg-rose-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
    </div>
  );
};

export default memo(ConditionNode);
