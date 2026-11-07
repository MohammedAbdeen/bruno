import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge,
  applyNodeChanges,
  applyEdgeChanges
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useDispatch, useSelector } from 'react-redux';
import toast from 'react-hot-toast';
import { updateFlowData } from 'providers/ReduxStore/slices/collections';
import { saveRequest, runFlow, importFlowFromJson } from 'providers/ReduxStore/slices/collections/actions';
import { nodeTypes } from './nodes';
import FlowDebugger from './FlowDebugger';
import { buildFlowCompletionScope } from './FlowExpressionInput/scope';
import { displayFlowEdges } from './graph-display';
import { duplicateFlowNodes, layoutFlowNodes } from './graph-editing';
import GenerateFlow from './GenerateFlow';
import FlowToolbar from './FlowToolbar';
import NodeInspector from './NodeInspector';
import FlowResultsDrawer from './FlowResultsDrawer';

const FlowTabPanel = ({ item, collection }) => {
  const dispatch = useDispatch();
  const [isTransferring, setIsTransferring] = useState(false);
  const aiPreferences = useSelector((state) => state.app.preferences?.ai);
  const [showGenerator, setShowGenerator] = useState(false);
  const cancelTokenRef = useRef(null);
  const canvasRef = useRef(null);
  const canvasElementRef = useRef(null);
  const flowData = useMemo(() => item.draft?.flow || item.flow || { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } }, [item.draft?.flow, item.flow]);

  const [nodes, setNodes] = useNodesState(flowData.nodes || []);
  const [edges, setEdges] = useEdgesState(flowData.edges || []);
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [isRunning, setIsRunning] = useState(false);
  const [executionLogs, setExecutionLogs] = useState([]);
  const [runSummary, setRunSummary] = useState(null);
  const [paused, setPaused] = useState(null);
  const [validation, setValidation] = useState(null);
  const [isValidating, setIsValidating] = useState(false);
  const [nodeMenu, setNodeMenu] = useState(null);
  const [isLayingOut, setIsLayingOut] = useState(false);
  const displayedNodes = useMemo(() => nodes.map((node) => ({ ...node, data: { ...node.data,
    validationIssues: validation?.issues.filter((issue) => issue.nodeId === node.id) || [], debugPaused: paused?.nodeId === node.id } })), [nodes, validation, paused]);
  const displayedEdges = useMemo(() => displayFlowEdges(nodes, edges), [nodes, edges]);
  const completionScope = useMemo(() => buildFlowCompletionScope({ ...flowData, nodes }, executionLogs, runSummary?.context || {}), [flowData, nodes, executionLogs, runSummary]);

  // File parsing and reloads can deliver the graph after the tab first opens.
  useEffect(() => {
    if (item.draft?.flow) return;
    setNodes(item.flow?.nodes || []);
    setEdges(item.flow?.edges || []);
  }, [item.uid, item.flow, item.draft?.flow, setNodes, setEdges]);

  const selectedNode = useMemo(
    () => nodes.find((n) => n.id === selectedNodeId),
    [nodes, selectedNodeId]
  );

  // Sync state back to Redux draft
  const syncToRedux = useCallback(
    (newNodes, newEdges, settings = {}) => {
      setValidation(null);
      dispatch(
        updateFlowData({
          itemUid: item.uid,
          collectionUid: collection.uid,
          flow: {
            nodes: newNodes,
            edges: newEdges,
            viewport: settings.viewport || flowData.viewport || { x: 0, y: 0, zoom: 1 },
            config: settings.config || flowData.config || {}
          }
        })
      );
    },
    [dispatch, item.uid, collection.uid, flowData.viewport, flowData.config]
  );

  const handleDeleteNodes = useCallback((ids) => {
    if (isRunning) return;
    const removed = new Set(ids);
    const nextNodes = nodes.filter((node) => !removed.has(node.id));
    const nextEdges = edges.filter((edge) => !removed.has(edge.source) && !removed.has(edge.target));
    setNodes(nextNodes);
    setEdges(nextEdges);
    if (removed.has(selectedNodeId)) setSelectedNodeId(null);
    syncToRedux(nextNodes, nextEdges);
  }, [isRunning, nodes, edges, selectedNodeId, setNodes, setEdges, syncToRedux]);

  const handleDuplicateNodes = (ids) => {
    if (isRunning) return;
    const copies = duplicateFlowNodes(nodes, ids);
    if (!copies.length) return;
    const next = [...nodes.map((node) => ({ ...node, selected: false })), ...copies];
    setNodes(next);
    setSelectedNodeId(copies[0].id);
    syncToRedux(next, edges);
    setNodeMenu(null);
  };

  const handleBeautify = () => {
    if (isRunning) return;
    const next = layoutFlowNodes(nodes, edges);
    setIsLayingOut(true);
    setNodes(next);
    syncToRedux(next, edges);
    setTimeout(() => {
      setIsLayingOut(false);
      canvasRef.current?.fitView({ duration: 300, padding: 0.15 });
    }, 250);
  };

  const handleCanvasKeyDown = (event) => {
    if (isRunning) return;
    if (event.target.closest('input, textarea, select, [contenteditable], [data-inspector]')) return;
    const duplicate = (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'd';
    if (!duplicate && !['Delete', 'Backspace'].includes(event.key)) return;
    const selected = nodes.filter((node) => node.selected).map((node) => node.id);
    if (!selected.length && selectedNodeId) selected.push(selectedNodeId);
    if (duplicate) {
      event.preventDefault();
      event.stopPropagation();
      handleDuplicateNodes(selected);
      return;
    }
    if (selected.length) {
      event.preventDefault();
      event.stopPropagation();
      handleDeleteNodes(selected);
    } else if (edges.some((edge) => edge.selected)) {
      event.preventDefault();
      event.stopPropagation();
      const next = edges.filter((edge) => !edge.selected);
      setEdges(next);
      syncToRedux(nodes, next);
    }
  };

  const handleConnect = useCallback((params) => {
    const next = addEdge(params, edges);
    setEdges(next);
    syncToRedux(nodes, next);
  }, [edges, nodes, setEdges, syncToRedux]);

  const handleNodesChangeWrapper = useCallback((changes) => {
    const next = applyNodeChanges(changes, nodes);
    const ids = new Set(next.map((node) => node.id));
    const nextEdges = edges.filter((edge) => ids.has(edge.source) && ids.has(edge.target));
    setNodes(next);
    if (nextEdges.length !== edges.length) setEdges(nextEdges);
    if (selectedNodeId && !ids.has(selectedNodeId)) setSelectedNodeId(null);
    if (changes.some((change) => ['position', 'remove', 'add', 'replace'].includes(change.type))) {
      syncToRedux(next, nextEdges);
    }
  }, [nodes, edges, selectedNodeId, setNodes, setEdges, syncToRedux]);

  const handleEdgesChangeWrapper = useCallback((changes) => {
    const next = applyEdgeChanges(changes, edges);
    setEdges(next);
    if (changes.some((change) => ['remove', 'add', 'replace'].includes(change.type))) {
      syncToRedux(nodes, next);
    }
  }, [nodes, edges, setEdges, syncToRedux]);

  const handleAddNode = useCallback(
    (type) => {
      const newNodeId = `${type}-${crypto.randomUUID()}`;
      const newNode = {
        id: newNodeId,
        type,
        position: {
          x: 250 + Math.random() * 80,
          y: 150 + Math.random() * 80
        },
        data: {
          ...(type === 'condition' ? { expression: 'res.status === 200' } : {}),
          ...(type === 'delay' ? { durationMs: 1000 } : {}),
          ...(type === 'script' ? { code: '// transform\n' } : {}),
          ...(type === 'request' ? { itemRef: '', outputVars: {} } : {}),
          ...(type === 'for-each' ? { expression: 'ctx.items', itemVar: 'item', indexVar: 'index', maxIterations: 1000 } : {}),
          ...(type === 'while' ? { expression: 'ctx.index < 3', maxIterations: 100 } : {}),
          ...(type === 'data-source' ? { fileRef: '', format: 'json', itemVar: 'row', indexVar: 'index', maxIterations: 1000 } : {}),
          ...(type === 'sub-flow' ? { flowRef: '' } : {}),
          ...(type === 'assert' ? { expression: 'res.status === 200', message: 'Expected status 200', failFast: true } : {})
        }
      };

      const next = [...nodes.map((node) => ({ ...node, selected: false })), { ...newNode, selected: true }];
      setNodes(next);
      syncToRedux(next, edges);
      setSelectedNodeId(newNodeId);
    },
    [nodes, setNodes, edges, syncToRedux]
  );

  const handleUpdateNode = useCallback(
    (nodeId, updatedData) => {
      const next = nodes.map((node) => node.id === nodeId ? { ...node, data: updatedData } : node);
      setNodes(next);
      syncToRedux(next, edges);
    },
    [nodes, setNodes, edges, syncToRedux]
  );

  // Listen for real-time IPC flow execution events from Electron
  useEffect(() => {
    const { ipcRenderer } = window;
    if (!ipcRenderer) return;

    const updateExecution = (record) => setExecutionLogs((logs) => {
      const index = logs.findIndex((entry) => entry.stepId === record.stepId);
      if (index < 0) return [...logs, record];
      return logs.map((entry, entryIndex) => entryIndex === index ? { ...entry, ...record } : entry);
    });
    const removeListener = ipcRenderer.on('main:flow-event', (eventData) => {
      if (eventData.flowUid !== item.uid) return;

      const { eventType, data } = eventData;

      if (eventType === 'flow:paused') {
        setPaused(data);
      } else if (eventType === 'flow:started') {
        setPaused(null);
        setIsRunning(true);
        setExecutionLogs([]);
        setRunSummary(null);
        // Clear previous run status on nodes
        setNodes((nds) => nds.map((n) => ({ ...n, data: { ...n.data, runStatus: null, conditionMet: null } })));
      } else if (eventType === 'flow:node-started') {
        setPaused(null);
        updateExecution({ ...data, status: 'running' });
        setNodes((nds) =>
          nds.map((n) =>
            n.id === data.nodeId ? { ...n, data: { ...n.data, runStatus: 'running' } } : n
          )
        );
      } else if (eventType === 'flow:node-completed') {
        updateExecution({ ...data, status: 'success' });
        setNodes((nds) =>
          nds.map((n) =>
            n.id === data.nodeId
              ? {
                  ...n,
                  data: {
                    ...n.data,
                    runStatus: 'success',
                    conditionMet: data.result?.conditionMet,
                    response: data.result?.response
                  }
                }
              : n
          )
        );
      } else if (eventType === 'flow:node-failed') {
        updateExecution({ ...data, status: 'failed' });
        setNodes((nds) =>
          nds.map((n) =>
            n.id === data.nodeId ? { ...n, data: { ...n.data, runStatus: 'failed' } } : n
          )
        );
      } else if (eventType === 'flow:finished') {
        setPaused(null);
        setIsRunning(false);
        setRunSummary(data);
        if (data.status === 'success') toast.success('Flow completed');
        else toast.error(data.error || `Flow ${data.status}`);
      } else if (eventType === 'flow:cancelled') {
        setPaused(null);
        setIsRunning(false);
        toast('Flow execution cancelled');
      }
    });

    return () => {
      if (typeof removeListener === 'function') {
        removeListener();
      }
    };
  }, [item.uid, setNodes]);

  const handleRunFlow = async (debug = false) => {
    setPaused(null);
    setIsRunning(true);
    setExecutionLogs([]);
    try {
      cancelTokenRef.current = `flow-${crypto.randomUUID()}`;
      await dispatch(runFlow(item, collection.uid, cancelTokenRef.current, { debug }));
    } catch (err) {
      setIsRunning(false);
    } finally {
      cancelTokenRef.current = null;
      setPaused(null);
      setIsRunning(false);
    }
  };

  const handleCancelFlow = async () => {
    if (cancelTokenRef.current) {
      try {
        await window.ipcRenderer.invoke('cancel-http-request', cancelTokenRef.current);
      } catch (error) {
        toast.error(error.message || 'Could not stop flow');
      }
    }
  };

  const handleResume = async (action, context) => {
    await window.ipcRenderer.invoke('renderer:flow-debug-command', { cancelTokenUid: cancelTokenRef.current, action, context });
    setPaused((current) => current?.pauseId === paused?.pauseId ? null : current);
  };

  const handleValidate = async () => {
    setIsValidating(true);
    try {
      setValidation(await window.ipcRenderer.invoke('renderer:validate-flow', { flow: { ...flowData, nodes, edges }, collection }));
    } catch (error) { toast.error(error.message || 'Could not validate flow'); } finally { setIsValidating(false); }
  };

  const handleImportFlow = async () => {
    setIsTransferring(true);
    try {
      await dispatch(importFlowFromJson(collection.uid));
    } finally {
      setIsTransferring(false);
    }
  };

  const handleExportFlow = async () => {
    setIsTransferring(true);
    try {
      const result = await window.ipcRenderer.invoke('renderer:export-flow-json', {
        name: item.draft?.name || item.name, collectionPath: collection.pathname,
        flow: { ...flowData, nodes, edges }
      });
      if (!result.canceled) toast.success('Flow exported as JSON');
    } catch (error) {
      toast.error(error.message || 'Could not export flow JSON');
    } finally {
      setIsTransferring(false);
    }
  };

  const handleSaveFlow = () => {
    dispatch(saveRequest(item.uid, collection.uid));
  };

  return (
    <div className="flex flex-col flex-1 h-full w-full relative overflow-hidden bg-zinc-100 dark:bg-zinc-900 select-none">
      <FlowToolbar
        isRunning={isRunning}
        onGenerateFlow={aiPreferences?.enabled ? () => setShowGenerator(true) : undefined}
        onRunFlow={() => handleRunFlow(false)}
        onDebugFlow={() => handleRunFlow(true)}
        onBeautify={handleBeautify}
        onValidateFlow={handleValidate}
        isValidating={isValidating}
        onCancelFlow={handleCancelFlow}
        onSaveFlow={handleSaveFlow}
        onImportFlow={handleImportFlow}
        onExportFlow={handleExportFlow}
        isTransferring={isTransferring}
        onAddNode={handleAddNode}
        hasDraft={Boolean(item.draft)}
      />

      <style>{'.flow-layout-animating .react-flow__node { transition: transform 250ms ease; }'}</style>
      {nodeMenu && (
        <div role="menu" aria-label="Node actions" onKeyDown={(event) => { if (event.key === 'Escape') setNodeMenu(null); }} className="fixed z-50 p-1 border rounded shadow bg-white dark:bg-zinc-900 text-xs" style={{ left: nodeMenu.x, top: nodeMenu.y }}>
          <button role="menuitem" onClick={() => handleDuplicateNodes([nodeMenu.id])} className="p-2">Duplicate Node</button>
          <button role="menuitem" onClick={() => setNodeMenu(null)} className="p-2">Close</button>
        </div>
      )}
      {paused && <FlowDebugger key={paused.pauseId} paused={paused} onResume={handleResume} />}
      {validation && (
        <section aria-label="Flow validation" className="p-3 border-b text-xs max-h-40 overflow-auto">
          <strong>{validation.valid ? 'Flow validation passed' : 'Flow has validation errors'}</strong>
          <ul>{validation.issues.map((issue, index) => (
            <li key={index} className={issue.severity === 'error' ? 'text-rose-600' : 'text-amber-600'}>
              {issue.nodeId && <button className="underline mr-1" onClick={() => setSelectedNodeId(issue.nodeId)}>{issue.nodeId}</button>}<span>{issue.message}</span>
            </li>
          ))}
          </ul>
        </section>
      )}
      {showGenerator && (
        <GenerateFlow
          collection={collection}
          defaultModel={aiPreferences?.defaultModel}
          isRunning={isRunning}
          onClose={() => setShowGenerator(false)}
          onApply={(flow) => {
            setNodes(flow.nodes);
            setEdges(flow.edges);
            setSelectedNodeId(null);
            setExecutionLogs([]);
            setRunSummary(null);
            syncToRedux(flow.nodes, flow.edges, flow);
            requestAnimationFrame(() => canvasRef.current?.fitView());
          }}
        />
      )}
      <div ref={canvasElementRef} tabIndex={0} role="group" aria-label="Flow canvas" onKeyDown={handleCanvasKeyDown} className="flex-1 w-full h-full relative outline-none">
        <ReactFlow
          className={isLayingOut ? 'flow-layout-animating' : ''}
          onNodeContextMenu={(event, node) => {
            event.preventDefault();
            if (!isRunning) setNodeMenu({ id: node.id, x: event.clientX, y: event.clientY });
          }}
          onInit={(instance) => { canvasRef.current = instance; }}
          nodes={displayedNodes}
          edges={displayedEdges}
          nodeTypes={nodeTypes}
          onNodesChange={handleNodesChangeWrapper}
          onEdgesChange={handleEdgesChangeWrapper}
          onConnect={handleConnect}
          deleteKeyCode={null}
          nodesDraggable={!isRunning}
          nodesConnectable={!isRunning}
          onNodeClick={(_, node) => {
            setSelectedNodeId(node.id); canvasElementRef.current?.focus({ preventScroll: true });
          }}
          onEdgeClick={() => {
            setSelectedNodeId(null); canvasElementRef.current?.focus({ preventScroll: true });
          }}
          onPaneClick={() => {
            setSelectedNodeId(null); setNodeMenu(null);
          }}
          fitView
        >
          <Background color="#999" gap={16} size={1} />
          <Controls />
          <MiniMap
            nodeStrokeWidth={3}
            className="border border-zinc-200 dark:border-zinc-700 rounded-lg overflow-hidden"
          />
        </ReactFlow>

        {selectedNode && (
          <NodeInspector
            selectedNode={selectedNode}
            collection={collection}
            onUpdateNode={handleUpdateNode}
            onDeleteNode={(id) => handleDeleteNodes([id])}
            isRunning={isRunning}
            completionScope={completionScope}
            executions={executionLogs.filter((entry) => entry.nodeId === selectedNode.id)}
            onClose={() => setSelectedNodeId(null)}
          />
        )}

        <FlowResultsDrawer
          logs={executionLogs}
          runSummary={runSummary}
          isRunning={isRunning}
        />
      </div>
    </div>
  );
};

export default FlowTabPanel;
