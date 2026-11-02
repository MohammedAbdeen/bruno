import React, { useState, useEffect } from 'react';
import { IconX, IconPlus, IconTrash } from '@tabler/icons';
import { flattenItems, isItemARequest } from 'utils/collections';

const NodeInspector = ({ selectedNode, collection, onUpdateNode, onClose }) => {
  if (!selectedNode) return null;

  const { id, type, data = {} } = selectedNode;
  const requests = (collection ? flattenItems(collection.items || []) : []).filter(isItemARequest);

  // Local state for output variable mappings
  const [outputVars, setOutputVars] = useState(
    Object.entries(data.outputVars || {}).map(([key, val]) => ({ key, val }))
  );

  useEffect(() => {
    setOutputVars(Object.entries(data.outputVars || {}).map(([key, val]) => ({ key, val })));
  }, [selectedNode.id]);

  const handleDataChange = (field, value) => {
    onUpdateNode(id, {
      ...data,
      [field]: value
    });
  };

  const handleAddOutputVar = () => {
    const updated = [...outputVars, { key: '', val: '' }];
    setOutputVars(updated);
  };

  const handleUpdateOutputVar = (index, field, value) => {
    const updated = [...outputVars];
    updated[index][field] = value;
    setOutputVars(updated);

    const varsObj = {};
    updated.forEach(({ key, val }) => {
      if (key && key.trim()) {
        varsObj[key.trim()] = val;
      }
    });
    handleDataChange('outputVars', varsObj);
  };

  const handleDeleteOutputVar = (index) => {
    const updated = outputVars.filter((_, i) => i !== index);
    setOutputVars(updated);

    const varsObj = {};
    updated.forEach(({ key, val }) => {
      if (key && key.trim()) {
        varsObj[key.trim()] = val;
      }
    });
    handleDataChange('outputVars', varsObj);
  };

  return (
    <div className="absolute right-0 top-0 bottom-0 w-80 bg-white dark:bg-zinc-800 border-l border-zinc-200 dark:border-zinc-700 shadow-xl z-20 flex flex-col">
      <div className="px-4 py-3 border-b border-zinc-200 dark:border-zinc-700 flex items-center justify-between">
        <div className="font-semibold text-sm uppercase text-zinc-700 dark:text-zinc-200">
          Configure {type} Node
        </div>
        <button
          onClick={onClose}
          className="text-zinc-400 hover:text-zinc-600 dark:hover:text-zinc-200"
        >
          <IconX size={16} />
        </button>
      </div>

      <div className="p-4 flex-1 overflow-y-auto space-y-4 text-xs">
        {type === 'request' && (
          <>
            <div>
              <label className="block font-medium mb-1 text-zinc-700 dark:text-zinc-300">
                Select Request from Collection
              </label>
              <select
                className="w-full p-2 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 text-zinc-800 dark:text-zinc-200"
                value={data.itemRef || ''}
                onChange={(e) => handleDataChange('itemRef', e.target.value)}
              >
                <option value="">-- Choose request --</option>
                {requests.map((req) => (
                  <option key={req.uid} value={req.pathname || req.name}>
                    {req.name} ({req.request?.method || 'GET'})
                  </option>
                ))}
              </select>
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <label className="font-medium text-zinc-700 dark:text-zinc-300">
                  Extract Response Variables
                </label>
                <button
                  type="button"
                  onClick={handleAddOutputVar}
                  className="flex items-center space-x-1 text-indigo-600 dark:text-indigo-400 font-medium hover:underline"
                >
                  <IconPlus size={12} />
                  <span>Add Var</span>
                </button>
              </div>

              {outputVars.map((item, idx) => (
                <div key={idx} className="flex items-center space-x-2 mb-2">
                  <input
                    type="text"
                    placeholder="Var Name (e.g. token)"
                    value={item.key}
                    onChange={(e) => handleUpdateOutputVar(idx, 'key', e.target.value)}
                    className="w-1/2 p-1.5 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 font-mono text-xs"
                  />
                  <input
                    type="text"
                    placeholder="res.body.token"
                    value={item.val}
                    onChange={(e) => handleUpdateOutputVar(idx, 'val', e.target.value)}
                    className="w-1/2 p-1.5 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 font-mono text-xs"
                  />
                  <button
                    type="button"
                    onClick={() => handleDeleteOutputVar(idx)}
                    className="text-zinc-400 hover:text-rose-500"
                  >
                    <IconTrash size={14} />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}

        {type === 'condition' && (
          <div>
            <label className="block font-medium mb-1 text-zinc-700 dark:text-zinc-300">
              JavaScript Expression
            </label>
            <input
              type="text"
              value={data.expression || ''}
              onChange={(e) => handleDataChange('expression', e.target.value)}
              placeholder="res.status === 200 && ctx.token"
              className="w-full p-2 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 font-mono text-xs text-zinc-800 dark:text-zinc-200"
            />
            <p className="mt-1 text-[11px] text-zinc-500">
              Evaluates boolean. Access variables via <code className="text-indigo-500">ctx.*</code> and response via <code className="text-indigo-500">res.*</code>.
            </p>
          </div>
        )}

        {type === 'delay' && (
          <div>
            <label className="block font-medium mb-1 text-zinc-700 dark:text-zinc-300">
              Duration (Milliseconds)
            </label>
            <input
              type="number"
              value={data.durationMs || 1000}
              onChange={(e) => handleDataChange('durationMs', Number(e.target.value))}
              min="0"
              step="100"
              className="w-full p-2 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 text-xs"
            />
          </div>
        )}

        {type === 'script' && (
          <div>
            <label className="block font-medium mb-1 text-zinc-700 dark:text-zinc-300">
              JavaScript Code
            </label>
            <textarea
              rows={8}
              value={data.code || ''}
              onChange={(e) => handleDataChange('code', e.target.value)}
              placeholder="// Manipulate ctx or perform calculations&#10;ctx.computedVal = res.body.data.length;"
              className="w-full p-2 border border-zinc-300 dark:border-zinc-600 rounded bg-white dark:bg-zinc-900 font-mono text-xs"
            />
          </div>
        )}

        {type === 'start' && (
          <div>
            <p className="text-zinc-500 text-xs">
              Entry node of the flow. Initial variables can be accessed downstream with <code className="text-indigo-500">ctx.variableName</code>.
            </p>
          </div>
        )}

        {type === 'end' && (
          <div>
            <p className="text-zinc-500 text-xs">
              Termination node of the flow. Flow stops execution when reaching this point.
            </p>
          </div>
        )}
      </div>
    </div>
  );
};

export default NodeInspector;
