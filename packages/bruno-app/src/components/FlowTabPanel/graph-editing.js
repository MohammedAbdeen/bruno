import dagre from '@dagrejs/dagre';
import cloneDeep from 'lodash/cloneDeep';

export const duplicateFlowNodes = (nodes, selectedIds) => {
  const ids = new Set(selectedIds);
  return nodes.filter((node) => ids.has(node.id)).map((node) => {
    const copy = cloneDeep(node);
    copy.id = `node-${crypto.randomUUID()}`;
    copy.position = { x: node.position.x + 40, y: node.position.y + 40 };
    copy.selected = true;
    delete copy.dragging;
    for (const key of ['runStatus', 'conditionMet', 'response', 'validationIssues', 'debugPaused']) delete copy.data?.[key];
    return copy;
  });
};

export const layoutFlowNodes = (nodes, edges) => {
  const graph = new dagre.graphlib.Graph({ multigraph: true }).setGraph({ rankdir: 'LR', ranksep: 100, nodesep: 65 }).setDefaultEdgeLabel(() => ({}));
  const ids = new Set(nodes.map((node) => node.id));
  nodes.forEach((node) => graph.setNode(node.id, { width: node.measured?.width || node.width || 260, height: node.measured?.height || node.height || 125 }));
  edges.forEach((edge) => { if (ids.has(edge.source) && ids.has(edge.target)) graph.setEdge(edge.source, edge.target, {}, edge.id); });
  dagre.layout(graph);
  return nodes.map((node) => {
    const layout = graph.node(node.id);
    return { ...node, position: { x: layout.x - layout.width / 2, y: layout.y - layout.height / 2 } };
  });
};
