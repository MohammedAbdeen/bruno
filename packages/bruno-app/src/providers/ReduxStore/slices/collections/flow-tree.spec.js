import reducer, { collectionLoadedFromTree, saveRequest } from 'providers/ReduxStore/slices/collections';

it('reloads flow graph data from disk over an existing collection item', () => {
  const item = { uid: 'flow', name: 'Workflow', type: 'flow', pathname: '/collection/flow.bru',
    flow: { nodes: [{ id: 'old', type: 'start' }], edges: [] } };
  const flow = { nodes: [{ id: 'updated', type: 'start' }], edges: [], config: { stopOnError: false } };
  const state = reducer({ collections: [{ uid: 'collection', pathname: '/collection', format: 'bru', items: [item], environments: [] }] },
    collectionLoadedFromTree({ collectionUid: 'collection', tree: { items: [{ ...item, flow }] } }));
  expect(state.collections[0].items[0].flow).toEqual(flow);
});

it('keeps saved graph edits after clearing the draft', () => {
  const flow = { nodes: [{ id: 'new', type: 'start' }], edges: [] };
  const item = { uid: 'flow', type: 'flow', flow: { nodes: [], edges: [] }, draft: { type: 'flow', flow } };
  const state = reducer({ collections: [{ uid: 'collection', items: [item] }] },
    saveRequest({ collectionUid: 'collection', itemUid: 'flow' }));
  expect(state.collections[0].items[0].flow).toEqual(flow);
  expect(state.collections[0].items[0].draft).toBeNull();
});
