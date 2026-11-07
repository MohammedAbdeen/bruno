export const displayFlowEdges = (nodes, edges) => {
  const backEdges = new Set();
  for (const loop of nodes.filter((node) => ['for-each', 'while', 'data-source'].includes(node.type))) {
    const body = edges.find((edge) => edge.source === loop.id && edge.sourceHandle === 'body');
    const pending = body ? [body.target] : [];
    const bodyIds = new Set();
    while (pending.length) {
      const id = pending.pop();
      if (id === loop.id || bodyIds.has(id)) continue;
      bodyIds.add(id);
      pending.push(...edges.filter((edge) => edge.source === id).map((edge) => edge.target));
    }
    for (const edge of edges) if (edge.target === loop.id && (bodyIds.has(edge.source) || edge.source === loop.id)) backEdges.add(edge.id);
  }
  return edges.map((edge) => backEdges.has(edge.id) ? { ...edge, type: 'smoothstep', animated: true,
    className: `${edge.className || ''} flow-loop-back-edge`.trim(), style: { ...edge.style, stroke: '#8b5cf6', strokeWidth: 2 } } : edge);
};
