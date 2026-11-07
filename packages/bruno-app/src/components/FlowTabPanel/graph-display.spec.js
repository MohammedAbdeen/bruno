import { displayFlowEdges } from './graph-display';

test('styles body return edges without changing entry edges or the saved graph', () => {
  const nodes = [{ id: 'start', type: 'start' }, { id: 'loop', type: 'for-each' }, { id: 'body', type: 'script' }];
  const edges = [{ id: 'entry', source: 'start', target: 'loop' }, { id: 'body', source: 'loop', target: 'body', sourceHandle: 'body' }, { id: 'return', source: 'body', target: 'loop' }];
  const result = displayFlowEdges(nodes, edges);
  expect(result[0].type).toBeUndefined();
  expect(result[2]).toMatchObject({ type: 'smoothstep', animated: true, className: 'flow-loop-back-edge' });
  expect(edges[2].type).toBeUndefined();
});
