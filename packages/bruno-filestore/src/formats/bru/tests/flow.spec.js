const { parseBruRequest, stringifyBruRequest } = require('../index');

describe('Flow item parsing and stringifying', () => {
  const sampleFlowBru = `meta {
  name: User Registration Flow
  type: flow
  seq: 1
}

flow {
  data: '''
    {
      "nodes": [
        {
          "id": "start-1",
          "type": "start",
          "position": {
            "x": 100,
            "y": 100
          },
          "data": {}
        },
        {
          "id": "req-1",
          "type": "request",
          "position": {
            "x": 300,
            "y": 100
          },
          "data": {
            "itemRef": "auth/register.bru"
          }
        }
      ],
      "edges": [
        {
          "id": "e1-2",
          "source": "start-1",
          "target": "req-1"
        }
      ],
      "viewport": {
        "x": 0,
        "y": 0,
        "zoom": 1
      },
      "config": {}
    }
  '''
}
`;

  it('should parse a flow bru string into an item object', () => {
    const item = parseBruRequest(sampleFlowBru);
    expect(item.type).toBe('flow');
    expect(item.name).toBe('User Registration Flow');
    expect(item.seq).toBe(1);
    expect(item.flow).toBeDefined();
    expect(item.flow.nodes).toHaveLength(2);
    expect(item.flow.nodes[0].id).toBe('start-1');
    expect(item.flow.edges).toHaveLength(1);
    expect(item.request).toBeNull();
  });

  it('should stringify a flow item back into bru format', () => {
    const item = {
      type: 'flow',
      name: 'User Registration Flow',
      seq: 1,
      flow: {
        nodes: [
          { id: 'start-1', type: 'start', position: { x: 100, y: 100 }, data: {} },
          { id: 'req-1', type: 'request', position: { x: 300, y: 100 }, data: { itemRef: 'auth/register.bru' } }
        ],
        edges: [
          { id: 'e1-2', source: 'start-1', target: 'req-1' }
        ],
        viewport: { x: 0, y: 0, zoom: 1 },
        config: {}
      }
    };

    const bruString = stringifyBruRequest(item);
    expect(bruString).toContain('name: User Registration Flow');
    expect(bruString).toContain('type: flow');
    expect(bruString).toContain('flow {');

    const reparsed = parseBruRequest(bruString);
    expect(reparsed.type).toBe('flow');
    expect(reparsed.flow.nodes).toHaveLength(2);
    expect(reparsed.flow.edges).toHaveLength(1);
  });
});
