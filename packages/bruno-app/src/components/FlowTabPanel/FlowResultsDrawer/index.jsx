import NodeExecutionDetails from '../NodeExecutionDetails';
import React, { useState } from 'react';
import { IconChevronUp, IconChevronDown, IconCheck, IconX, IconClock } from '@tabler/icons';

const FlowResultsDrawer = ({ logs = [], runSummary, isRunning }) => {
  const [isExpanded, setIsExpanded] = useState(false);
  const [activeTab, setActiveTab] = useState('timeline'); // 'timeline' | 'context'

  if (!logs.length && !isRunning && !runSummary) {
    return null;
  }

  return (
    <div
      className={`absolute left-0 right-0 bottom-0 bg-white dark:bg-zinc-800 border-t border-zinc-200 dark:border-zinc-700 shadow-2xl transition-all duration-200 z-20 flex flex-col ${
        isExpanded ? 'h-64' : 'h-10'
      }`}
    >
      {/* Header bar */}
      <div
        onClick={() => setIsExpanded(!isExpanded)}
        className="h-10 px-4 bg-zinc-100 dark:bg-zinc-900 border-b border-zinc-200 dark:border-zinc-700 flex items-center justify-between cursor-pointer select-none text-xs"
      >
        <div className="flex items-center space-x-3">
          <span className="font-semibold text-zinc-700 dark:text-zinc-200">
            Flow Execution Timeline
          </span>
          {runSummary && (
            <span
              className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
                runSummary.status === 'success'
                  ? 'bg-emerald-500/10 text-emerald-600'
                  : 'bg-rose-500/10 text-rose-600'
              }`}
            >
              {runSummary.status} ({runSummary.duration}ms)
            </span>
          )}
          <span className="text-zinc-400 text-[11px]">
            {logs.length} step{logs.length === 1 ? '' : 's'} recorded
          </span>
        </div>

        <div className="flex items-center space-x-2 text-zinc-500">
          <span>{isExpanded ? 'Collapse' : 'Expand'}</span>
          {isExpanded ? <IconChevronDown size={14} /> : <IconChevronUp size={14} />}
        </div>
      </div>

      {/* Expanded body */}
      {isExpanded && (
        <div className="flex-1 flex flex-col overflow-hidden">
          {/* Sub-tabs */}
          <div className="px-4 py-1.5 border-b border-zinc-200 dark:border-zinc-700 flex space-x-4 text-xs font-medium">
            <button
              onClick={() => setActiveTab('timeline')}
              className={`${
                activeTab === 'timeline'
                  ? 'text-indigo-600 border-b-2 border-indigo-600 pb-1'
                  : 'text-zinc-500 hover:text-zinc-700'
              }`}
            >
              Steps ({logs.length})
            </button>
            <button
              onClick={() => setActiveTab('context')}
              className={`${
                activeTab === 'context'
                  ? 'text-indigo-600 border-b-2 border-indigo-600 pb-1'
                  : 'text-zinc-500 hover:text-zinc-700'
              }`}
            >
              Context Variables
            </button>
          </div>

          <div className="flex-1 p-3 overflow-y-auto text-xs">
            {runSummary?.error && <p className="text-rose-500 mb-2">{runSummary.error}</p>}
            {activeTab === 'timeline' && (
              <div className="space-y-2">
                {logs.map((log, idx) => (
                  <div
                    key={log.stepId || idx}
                    className="p-2 rounded bg-zinc-50 dark:bg-zinc-900 border border-zinc-200 dark:border-zinc-700 flex items-center justify-between flex-wrap"
                  >
                    <div className="flex items-center space-x-2">
                      {log.status === 'running' ? <IconClock size={12} /> : log.status === 'success' ? (
                        <div className="p-1 rounded bg-emerald-500/10 text-emerald-500">
                          <IconCheck size={12} />
                        </div>
                      ) : (
                        <div className="p-1 rounded bg-rose-500/10 text-rose-500">
                          <IconX size={12} />
                        </div>
                      )}
                      <span className="font-semibold uppercase text-zinc-700 dark:text-zinc-300">
                        {log.nodeType}
                      </span>
                      <span className="text-zinc-400 font-mono text-[11px]">
                        [{log.nodeId}]
                      </span>
                      {log.result?.assertions && <span>{log.result.assertions.filter((check) => check.passed).length}/{log.result.assertions.length} assertions passed</span>}
                      {(log.response || log.result?.response) && (
                        <span className="text-emerald-600 font-mono text-[11px]">
                          HTTP {(log.response || log.result?.response).status}
                        </span>
                      )}
                      {log.error && (
                        <span className="text-rose-500 text-[11px]">
                          Error: {log.error}
                        </span>
                      )}
                    </div>
                    <div className="flex items-center space-x-1 text-zinc-400 font-mono text-[10px]">
                      <IconClock size={10} />
                      <span>{log.duration || 0}ms</span>
                    </div>
                    <details className="basis-full w-full mt-2">
                      <summary className="cursor-pointer text-indigo-600">View input / output</summary>
                      <div className="mt-2"><NodeExecutionDetails executions={[log]} /></div>
                    </details>
                  </div>
                ))}
              </div>
            )}

            {activeTab === 'context' && (
              <div className="bg-zinc-50 dark:bg-zinc-900 p-3 rounded font-mono text-xs overflow-auto max-h-48 border border-zinc-200 dark:border-zinc-700">
                <pre>{JSON.stringify(runSummary?.context || {}, null, 2)}</pre>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default FlowResultsDrawer;
