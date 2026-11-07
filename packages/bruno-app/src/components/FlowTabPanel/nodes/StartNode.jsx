import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconPlayerPlay } from '@tabler/icons';

const StartNode = ({ data, selected }) => {
  const varCount = Object.keys(data?.initialVars || {}).length;

  return (
    <div
      className={`px-4 py-3 shadow-md rounded-lg bg-white dark:bg-zinc-800 border-2 transition-colors ${
        selected ? 'border-indigo-500 shadow-indigo-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700'
      } min-w-[180px]`}
    >
      <div className="flex items-center space-x-2">
        <div className="p-1.5 rounded bg-emerald-500/10 text-emerald-500">
          <IconPlayerPlay size={16} />
        </div>
        <div>
          <div className="text-xs font-bold text-zinc-800 dark:text-zinc-200 uppercase tracking-wide">
            Start
          </div>
          <div className="text-[10px] text-zinc-500">
            {varCount > 0 ? `${varCount} initial vars` : 'Entry point'}
          </div>
        </div>
      </div>

      <Handle
        type="source"
        position={Position.Right}
        className="w-3 h-3 bg-emerald-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
    </div>
  );
};

export default memo(StartNode);
