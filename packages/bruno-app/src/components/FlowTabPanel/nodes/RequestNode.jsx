import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import { IconSend, IconLoader2, IconCheck, IconX } from '@tabler/icons';

const RequestNode = ({ data, selected }) => {
  const { itemRef, itemUid, outputVars = {}, runStatus, response } = data || {};
  const outputCount = Object.keys(outputVars).length;

  let statusBadge = null;
  let borderClass = selected ? 'border-indigo-500 shadow-indigo-500/20 shadow-lg' : 'border-zinc-300 dark:border-zinc-700';

  if (runStatus === 'running') {
    borderClass = 'border-blue-500 ring-2 ring-blue-500/30 animate-pulse';
    statusBadge = (
      <span className="flex items-center text-blue-500 text-[10px] space-x-1">
        <IconLoader2 size={12} className="animate-spin" />
        <span>Running</span>
      </span>
    );
  } else if (runStatus === 'success') {
    borderClass = 'border-emerald-500';
    statusBadge = (
      <span className="flex items-center text-emerald-500 text-[10px] space-x-1">
        <IconCheck size={12} />
        <span>{response?.status || '200'} OK</span>
      </span>
    );
  } else if (runStatus === 'failed') {
    borderClass = 'border-rose-500';
    statusBadge = (
      <span className="flex items-center text-rose-500 text-[10px] space-x-1">
        <IconX size={12} />
        <span>Failed</span>
      </span>
    );
  }

  return (
    <div
      className={`px-4 py-3 shadow-md rounded-lg bg-white dark:bg-zinc-800 border-2 transition-all min-w-[220px] max-w-[280px] ${borderClass}`}
    >
      <Handle
        type="target"
        position={Position.Left}
        className="w-3 h-3 bg-zinc-400 dark:bg-zinc-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />

      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center space-x-2">
          <div className="p-1 rounded bg-indigo-500/10 text-indigo-500">
            <IconSend size={14} />
          </div>
          <span className="text-xs font-semibold uppercase text-zinc-700 dark:text-zinc-300">
            Request
          </span>
        </div>
        {statusBadge}
      </div>

      <div className="text-sm font-medium truncate text-zinc-900 dark:text-zinc-100" title={itemRef || itemUid || 'Unconfigured Request'}>
        {itemRef || itemUid || 'Select request...'}
      </div>

      {outputCount > 0 && (
        <div className="mt-2 text-[10px] text-zinc-500 flex items-center space-x-1">
          <span className="px-1.5 py-0.5 rounded bg-zinc-100 dark:bg-zinc-700 font-mono">
            +{outputCount} vars
          </span>
        </div>
      )}

      <Handle
        type="source"
        position={Position.Right}
        className="w-3 h-3 bg-indigo-500 border-2 border-white dark:border-zinc-800 rounded-full"
      />
    </div>
  );
};

export default memo(RequestNode);
