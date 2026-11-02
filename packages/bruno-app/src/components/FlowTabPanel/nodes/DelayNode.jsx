import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconClock } from '@tabler/icons';

const DelayNode = ({ data, selected }) => {
  const { durationMs = 1000, runStatus } = data || {};

  let borderClass = selected ? 'border-indigo-500 shadow-indigo-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700';
  if (runStatus === 'running') {
    borderClass = 'border-purple-500 ring-2 ring-purple-500/30 animate-pulse';
  } else if (runStatus === 'success') {
    borderClass = 'border-emerald-500';
  }

  return (
    <div
      className={`px-4 py-3 shadow-md rounded-lg bg-white dark:bg-zinc-800 border-2 transition-all min-w-[170px] ${borderClass}`}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="w-3 h-3 bg-zinc-400 dark:bg-zinc-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />

      <div className="flex items-center space-x-2">
        <div className="p-1.5 rounded bg-purple-500/10 text-purple-500">
          <IconClock size={16} />
        </div>
        <div>
          <div className="text-xs font-bold uppercase text-zinc-800 dark:text-zinc-200">
            Delay
          </div>
          <div className="text-xs font-mono text-zinc-500">
            {durationMs} ms
          </div>
        </div>
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="w-3 h-3 bg-purple-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
    </div>
  );
};

export default memo(DelayNode);
