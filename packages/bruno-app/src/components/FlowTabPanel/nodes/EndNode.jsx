import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconFlag } from '@tabler/icons';

const EndNode = ({ data, selected }) => {
  const { runStatus } = data || {};

  let borderClass = selected ? 'border-indigo-500 shadow-indigo-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700';
  if (runStatus === 'success') {
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
        <div className="p-1.5 rounded bg-zinc-500/10 text-zinc-500 dark:text-zinc-400">
          <IconFlag size={16} />
        </div>
        <div>
          <div className="text-xs font-bold uppercase text-zinc-800 dark:text-zinc-200">
            End
          </div>
          <div className="text-[10px] text-zinc-500">
            Flow termination
          </div>
        </div>
      </div>
    </div>
  );
};

export default memo(EndNode);
