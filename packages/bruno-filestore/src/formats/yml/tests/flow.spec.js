const { parseYmlItem, stringifyYmlItem } = require('../index');
const sample = require('../../../../../../examples/flows/sum-flow.json');

describe('YAML flow persistence', () => {
  it('round-trips the exact Sum Example graph and metadata', () => {
    const input = { type: 'flow', name: sample.name, seq: 3, tags: ['example'], settings: {}, flow: sample.flow };
    const yml = stringifyYmlItem(input);
    const parsed = parseYmlItem(yml);
    expect(parsed.type).toBe('flow');
    expect(parsed.name).toBe('Sum Example');
    expect(parsed.flow).toEqual(sample.flow);
    expect(parsed.seq).toBe(3);
    expect(parsed.tags).toEqual(['example']);
    expect(parsed.request).toBeNull();
  });
  it('supports new flows without an existing graph', () => {
    const parsed = parseYmlItem(stringifyYmlItem({ type: 'flow', name: 'New Flow' }));
    expect(parsed.flow).toEqual({ nodes: [], edges: [] });
  });
});
