const bruToJson = require('../src/bruToJson');
const jsonToBru = require('../src/jsonToBru');

describe('flow block', () => {
  const sampleFlow = {
    nodes: [
      {
        id: 'start-1',
        type: 'start',
        position: { x: 100, y: 150 },
        data: { initialVars: { env: 'production' } }
      },
      {
        id: 'req-1',
        type: 'request',
        position: { x: 300, y: 150 },
        data: { itemRef: 'auth/login.bru' }
      }
    ],
    edges: [
      {
        id: 'e1-2',
        source: 'start-1',
        target: 'req-1'
      }
    ],
    viewport: { x: 0, y: 0, zoom: 1 },
    config: { stopOnError: true }
  };

  it('should serialize and parse a flow block round-trip', () => {
    const input = {
      meta: {
        name: 'User Onboarding Flow',
        type: 'flow',
        seq: 1
      },
      flow: sampleFlow
    };

    const bruString = jsonToBru(input);
    expect(bruString).toContain('meta {');
    expect(bruString).toContain('name: User Onboarding Flow');
    expect(bruString).toContain('type: flow');
    expect(bruString).toContain('flow {');
    expect(bruString).toContain('data: \'\'\'');

    const parsed = bruToJson(bruString);
    expect(parsed.meta.type).toBe('flow');
    expect(parsed.meta.name).toBe('User Onboarding Flow');
    expect(parsed.flow).toBeDefined();
    expect(parsed.flow.nodes).toHaveLength(2);
    expect(parsed.flow.nodes[0].id).toBe('start-1');
    expect(parsed.flow.nodes[0].type).toBe('start');
    expect(parsed.flow.nodes[1].data.itemRef).toBe('auth/login.bru');
    expect(parsed.flow.edges).toHaveLength(1);
    expect(parsed.flow.edges[0].source).toBe('start-1');
    expect(parsed.flow.edges[0].target).toBe('req-1');
    expect(parsed.flow.config.stopOnError).toBe(true);
  });

  it('should handle empty flow gracefully', () => {
    const input = {
      meta: {
        name: 'Empty Flow',
        type: 'flow'
      },
      flow: {
        nodes: [],
        edges: []
      }
    };

    const bru = jsonToBru(input);
    const parsed = bruToJson(bru);
    expect(parsed.flow.nodes).toEqual([]);
    expect(parsed.flow.edges).toEqual([]);
  });
});

describe('Phase 4 flow persistence', () => {
  it('preserves advanced nodes, configuration, and loop handles', () => {
    const nodes = [
      { id: 'loop', type: 'for-each', data: { expression: 'ctx.items', itemVar: 'item', maxIterations: 5 } },
      { id: 'poll', type: 'while', data: { expression: '!res.body.ready', maxIterations: 10 } },
      { id: 'child', type: 'sub-flow', data: { flowRef: 'child.flow.bru' } },
      { id: 'dataset', type: 'data-source', data: { fileRef: 'data/users.csv', format: 'csv' } },
      { id: 'assert', type: 'assert', data: { assertions: [{ expression: 'res.status === 200' }], failFast: false } }
    ];
    const edges = [{ id: 'body', source: 'loop', target: 'child', sourceHandle: 'body' },
      { id: 'done', source: 'loop', target: 'assert', sourceHandle: 'done' }];
    const parsed = bruToJson(jsonToBru({ meta: { type: 'flow', name: 'Advanced' }, flow: { nodes, edges } }));
    expect(parsed.flow.nodes).toEqual(nodes);
    expect(parsed.flow.edges).toEqual(edges);
  });
});
