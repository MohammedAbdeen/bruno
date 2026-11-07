import React from 'react';
import {
  IconPlayerPlay,
  IconPlayerStop,
  IconDeviceFloppy,
  IconSend,
  IconGitBranch,
  IconClock,
  IconCode,
  IconFlag,
  IconLoader2
} from '@tabler/icons';

const FlowToolbar = ({
  isRunning,
  onRunFlow,
  onDebugFlow,
  onBeautify,
  onValidateFlow,
  isValidating,
  onCancelFlow,
  onSaveFlow,
  onImportFlow,
  onExportFlow,
  isTransferring,
  onAddNode,
  onGenerateFlow,
  hasDraft
}) => {
  return (
    <div className="min-h-12 px-4 py-2 gap-2 flex-wrap border-b border-zinc-200 dark:border-zinc-700 bg-zinc-50 dark:bg-zinc-800 flex items-center justify-between shrink-0 z-10">
      {/* Left: Execution Controls */}
      <div className="flex items-center space-x-2">
        {isRunning ? (
          <button
            onClick={onCancelFlow}
            className="flex items-center space-x-1 px-3 py-1.5 rounded bg-rose-600 hover:bg-rose-700 text-white font-medium text-xs transition"
          >
            <IconPlayerStop size={14} />
            <span>Stop Flow</span>
          </button>
        ) : (
          <button
            onClick={onRunFlow}
            className="flex items-center space-x-1 px-3 py-1.5 rounded bg-emerald-600 hover:bg-emerald-700 text-white font-medium text-xs transition shadow-sm"
          >
            <IconPlayerPlay size={14} />
            <span>Run Flow</span>
          </button>
        )}

        <button disabled={isRunning} onClick={onBeautify} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">Beautify Graph</button>
        <button disabled={isRunning} onClick={onDebugFlow} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">Debug Flow</button>
        <button disabled={isRunning || isValidating} onClick={onValidateFlow} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">Validate Flow</button>
        <button
          onClick={onSaveFlow}
          disabled={!hasDraft}
          className={`flex items-center space-x-1 px-3 py-1.5 rounded text-xs transition font-medium ${
            hasDraft
              ? 'bg-zinc-200 dark:bg-zinc-700 text-zinc-900 dark:text-zinc-100 hover:bg-zinc-300 dark:hover:bg-zinc-600'
              : 'opacity-40 cursor-not-allowed text-zinc-500'
          }`}
        >
          <IconDeviceFloppy size={14} />
          <span>Save</span>
        </button>

        <button onClick={onImportFlow} disabled={isRunning || isTransferring} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">
          Import JSON
        </button>
        <button onClick={onExportFlow} disabled={isRunning || isTransferring} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">
          Export JSON
        </button>

        {onGenerateFlow && (
          <button onClick={onGenerateFlow} disabled={isRunning} className="px-3 py-1.5 rounded border text-xs disabled:opacity-40">
            Generate Flow
          </button>
        )}
        {isRunning && (
          <div className="flex items-center space-x-1.5 text-xs text-blue-500 ml-2">
            <IconLoader2 size={14} className="animate-spin" />
            <span>Executing flow...</span>
          </div>
        )}
      </div>

      {/* Right: Add Nodes Palette */}
      <div className="flex items-center gap-1 flex-wrap">
        <span className="text-[11px] font-semibold uppercase text-zinc-400 mr-1">
          Add Node:
        </span>
        <button
          onClick={() => onAddNode('request')}
          className="flex items-center space-x-1 px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 hover:border-indigo-400 text-zinc-700 dark:text-zinc-200 text-xs transition"
        >
          <IconSend size={12} className="text-indigo-500" />
          <span>Request</span>
        </button>

        <button
          onClick={() => onAddNode('condition')}
          className="flex items-center space-x-1 px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 hover:border-amber-400 text-zinc-700 dark:text-zinc-200 text-xs transition"
        >
          <IconGitBranch size={12} className="text-amber-500" />
          <span>Condition</span>
        </button>

        <button
          onClick={() => onAddNode('delay')}
          className="flex items-center space-x-1 px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 hover:border-purple-400 text-zinc-700 dark:text-zinc-200 text-xs transition"
        >
          <IconClock size={12} className="text-purple-500" />
          <span>Delay</span>
        </button>

        <button
          onClick={() => onAddNode('script')}
          className="flex items-center space-x-1 px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 hover:border-teal-400 text-zinc-700 dark:text-zinc-200 text-xs transition"
        >
          <IconCode size={12} className="text-teal-500" />
          <span>Script</span>
        </button>

        {[
          ['start', 'Start'], ['for-each', 'For Each'], ['while', 'While'], ['sub-flow', 'Sub-flow'],
          ['data-source', 'Dataset'], ['assert', 'Assert']
        ].map(([type, label]) => (
          <button
            key={type}
            onClick={() => onAddNode(type)}
            disabled={isRunning}
            className="px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 text-xs"
          >
            {label}
          </button>
        ))}

        <button
          onClick={() => onAddNode('end')}
          className="flex items-center space-x-1 px-2.5 py-1 rounded bg-white dark:bg-zinc-700 border border-zinc-200 dark:border-zinc-600 hover:border-zinc-400 text-zinc-700 dark:text-zinc-200 text-xs transition"
        >
          <IconFlag size={12} className="text-zinc-500" />
          <span>End</span>
        </button>
      </div>
    </div>
  );
};

export default FlowToolbar;
