import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  addEdge
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useDispatch } from 'react-redux';
import toast from 'react-hot-toast';
import { updateFlowData } from 'providers/ReduxStore/slices/collections';
import { saveRequest, runFlow } from 'providers/ReduxStore/slices/collections/actions';
import { nodeTypes } from './nodes';
import FlowToolbar from './FlowToolbar';
import NodeInspector from './NodeInspector';
import FlowResultsDrawer from './FlowResultsDrawer';

const FlowTabPanel = ({ item, collection }) => {
  const dispatch = useDispatch();
  const flowData = item.draft?.flow || item.flow || { nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } };

  const [nodes, setNodes, onNodesChange] = useNodesState(flowData.nodes || []);
  const [edges, setEdges, onEdgesChange] = useEdgesState(flowData.edges || []);
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [isRunning, setIsRunning] = useState(false);
  const [executionLogs, setExecutionLogs] = useState([]);
  const [runSummary, setRunSummary] = useState(null);

  const selectedNode = useMemo(
    () => nodes.find((n) => n.id === selectedNodeId),
    [nodes, selectedNodeId]
  );

  // Sync state back to Redux draft
  const syncToRedux = useCallback(
    (newNodes, newEdges) => {
      dispatch(
        updateFlowData({
          itemUid: item.uid,
          collectionUid: collection.uid,
          flow: {
            nodes: newNodes,
            edges: newEdges,
            viewport: flowData.viewport || { x: 0, y: 0, zoom: 1 },
            config: flowData.config || {}
          }
        })
      );
    },
    [dispatch, item.uid, collection.uid, flowData.viewport, flowData.config]
  );

  const handleConnect = useCallback(
    (params) => {
      setEdges((eds) => {
        const next = addEdge(params, eds);
        syncToRedux(nodes, next);
        return next;
      });
    },
    [setEdges, nodes, syncToRedux]
  );

  const handleNodesChangeWrapper = useCallback(
    (changes) => {
      onNodesChange(changes);
      setNodes((currentNodes) => {
        syncToRedux(currentNodes, edges);
        return currentNodes;
      });
    },
    [onNodesChange, setNodes, edges, syncToRedux]
  );

  const handleEdgesChangeWrapper = useCallback(
    (changes) => {
      onEdgesChange(changes);
      setEdges((currentEdges) => {
        syncToRedux(nodes, currentEdges);
        return currentEdges;
      });
    },
    [onEdgesChange, setEdges, nodes, syncToRedux]
  );

  const handleAddNode = useCallback(
    (type) => {
      const newNodeId = `${type}-${Date.now().toString().slice(-4)}`;
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
          ...(type === 'request' ? { itemRef: '', outputVars: {} } : {})
        }
      };

      setNodes((nds) => {
        const next = [...nds, newNode];
        syncToRedux(next, edges);
        return next;
      });
      setSelectedNodeId(newNodeId);
    },
    [setNodes, edges, syncToRedux]
  );

  const handleUpdateNode = useCallback(
    (nodeId, updatedData) => {
      setNodes((nds) => {
        const next = nds.map((n) => (n.id === nodeId ? { ...n, data: updatedData } : n));
        syncToRedux(next, edges);
        return next;
      });
    },
    [setNodes, edges, syncToRedux]
  );

  // Listen for real-time IPC flow execution events from Electron
  useEffect(() => {
    const { ipcRenderer } = window;
    if (!ipcRenderer) return;

    const removeListener = ipcRenderer.on('main:flow-event', (eventData) => {
      if (eventData.flowUid !== item.uid) return;

      const { eventType, data } = eventData;

      if (eventType === 'flow:started') {
        setIsRunning(true);
        setExecutionLogs([]);
        setRunSummary(null);
        // Clear previous run status on nodes
        setNodes((nds) => nds.map((n) => ({ ...n, data: { ...n.data, runStatus: null, conditionMet: null } })));
      } else if (eventType === 'flow:node-started') {
        setNodes((nds) =>
          nds.map((n) =>
            n.id === data.nodeId ? { ...n, data: { ...n.data, runStatus: 'running' } } : n
          )
        );
      } else if (eventType === 'flow:node-completed') {
        setExecutionLogs((logs) => [...logs, data]);
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
        setExecutionLogs((logs) => [...logs, { ...data, status: 'failed' }]);
        setNodes((nds) =>
          nds.map((n) =>
            n.id === data.nodeId ? { ...n, data: { ...n.data, runStatus: 'failed' } } : n
          )
        );
      } else if (eventType === 'flow:finished') {
        setIsRunning(false);
        setRunSummary(data);
        toast.success(`Flow finished with status: ${data.status}`);
      } else if (eventType === 'flow:cancelled') {
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

  const handleRunFlow = async () => {
    setIsRunning(true);
    setExecutionLogs([]);
    try {
      await dispatch(runFlow(item, collection.uid));
    } catch (err) {
      setIsRunning(false);
    }
  };

  const handleCancelFlow = () => {
    setIsRunning(false);
  };

  const handleSaveFlow = () => {
    dispatch(saveRequest(item.uid, collection.uid));
  };

  return (
    <div className="flex flex-col flex-1 h-full w-full relative overflow-hidden bg-zinc-100 dark:bg-zinc-900 select-none">
      <FlowToolbar
        isRunning={isRunning}
        onRunFlow={handleRunFlow}
        onCancelFlow={handleCancelFlow}
        onSaveFlow={handleSaveFlow}
        onAddNode={handleAddNode}
        hasDraft={Boolean(item.draft)}
      />

      <div className="flex-1 w-full h-full relative">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodesChange={handleNodesChangeWrapper}
          onEdgesChange={handleEdgesChangeWrapper}
          onConnect={handleConnect}
          onNodeClick={(_, node) => setSelectedNodeId(node.id)}
          onPaneClick={() => setSelectedNodeId(null)}
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
