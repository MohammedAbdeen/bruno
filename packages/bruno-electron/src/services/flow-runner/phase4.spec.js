const Engine = require('./flow-execution-engine');
const { parseDataset, resolveCollectionFile, loadFlowFile } = require('./flow-resources');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const node = (id, type, data = {}) => ({ id, type, data });
const edge = (source, target, sourceHandle) => ({ id: `${source}-${target}`, source, target, sourceHandle });
const linear = (...nodes) => ({ flow: { nodes, edges: nodes.slice(1).map((n, i) => edge(nodes[i].id, n.id)) } });
const loop = (type, data, body = node('work', 'script', { code: 'ctx.values = [...(ctx.values || []), ctx.item];' })) => ({
  flow: { nodes: [node('start', 'start'), node('loop', type, data), body, node('end', 'end')],
    edges: [edge('start', 'loop'), edge('loop', body.id, 'body'), edge(body.id, 'loop'), edge('loop', 'end', 'done')] }
});
const run = (flowItem, options = {}) => new Engine().run({ flowItem, ...options });

describe('Phase 4 flow execution', () => {
  test('for-each executes every item and retains repeated step results', async () => {
    const result = await run(loop('for-each', { expression: '[1, 2, 3]' }));
    expect(result.status).toBe('success');
    expect(result.context.values).toEqual([1, 2, 3]);
    expect(result.steps.filter((s) => s.nodeId === 'work')).toHaveLength(3);
  });
  test('empty arrays skip the body; invalid input fails', async () => {
    expect((await run(loop('for-each', { expression: '[]' }))).executedNodes).toEqual(['start', 'loop', 'end']);
    expect((await run(loop('for-each', { expression: '42' }))).error).toMatch('array');
  });
  test('missing body and missing return edges fail', async () => {
    for (const source of ['loop', 'work']) {
      const item = loop('for-each', { expression: '[1]' });
      item.flow.edges = item.flow.edges.filter((e) => e.source !== source);
      expect((await run(item)).status).toBe('failed');
    }
  });
  test('nested loops reset inner iteration on each outer pass', async () => {
    const result = await run({ flow: {
      nodes: [node('start', 'start'), node('outer', 'for-each', { expression: '[1,2]', itemVar: 'outer' }),
        node('inner', 'for-each', { expression: '[3,4]', itemVar: 'inner' }),
        node('work', 'script', { code: 'ctx.total = (ctx.total || 0) + ctx.outer * ctx.inner;' }), node('end', 'end')],
      edges: [edge('start', 'outer'), edge('outer', 'inner', 'body'), edge('inner', 'work', 'body'),
        edge('work', 'inner'), edge('inner', 'outer', 'done'), edge('outer', 'end', 'done')]
    } });
    expect(result.status).toBe('success');
    expect(result.context.total).toBe(21);
  });
  test('polling evaluates the latest normalized response', async () => {
    let calls = 0;
    const result = await run(loop('while', { expression: '!res.body?.ready', maxIterations: 3 }, node('poll', 'request')), {
      executeRequest: async () => ({ status: 200, data: { ready: ++calls === 3 } })
    });
    expect(result.status).toBe('success');
    expect(calls).toBe(3);
  });
  test('iteration limits stop infinite loops', async () => {
    expect((await run(loop('while', { expression: 'true', maxIterations: 2 }))).error).toMatch('iteration limit');
  });
  test.each([['json', '[{"name":"Alice"},{"name":"Bob"}]'], ['csv', 'name\r\nAlice\r\nBob\r\n']])('runs each %s record', async (format, content) => {
    const result = await run(loop('data-source', { fileRef: 'data/users', format, itemVar: 'item' }), {
      loadDataset: async (ref) => {
        expect(ref).toBe('data/users'); return content;
      }
    });
    expect(result.status).toBe('success');
    expect(result.context.values).toEqual([{ name: 'Alice' }, { name: 'Bob' }]);
  });
  test('dataset load failures propagate', async () => {
    const result = await run(loop('data-source', { fileRef: 'missing.json' }), { loadDataset: async () => { throw new Error('Missing dataset'); } });
    expect(result.error).toBe('Missing dataset');
  });
  test('sub-flows inherit context and return output without ending parent telemetry', async () => {
    const events = [];
    const result = await run(linear(node('start', 'start', { initialVars: { token: 'abc' } }), node('child', 'sub-flow', { flowRef: 'child.flow.bru' }), node('end', 'end')), {
      resolveFlow: async (ref) => {
        expect(ref).toBe('child.flow.bru');
        return linear(node('start', 'start'), node('script', 'script', { code: 'ctx.result = ctx.token + "123";' }));
      }, onEvent: (type) => events.push(type)
    });
    expect(result.status).toBe('success');
    expect(result.context.result).toBe('abc123');
    expect(events.filter((type) => type === 'flow:finished')).toHaveLength(1);
    expect(events).toContain('flow:subflow-event');
  });
  test('recursive sub-flows are bounded', async () => {
    const child = linear(node('child', 'sub-flow', { flowRef: 'child.flow.bru' }));
    expect((await run(child, { resolveFlow: async () => child })).error).toMatch('nesting limit');
  });
  test('assertions read extracted normalized outputs and fail fast', async () => {
    const result = await run(linear(node('req', 'request', { outputVars: { value: 'res.body.value' } }),
      node('assert', 'assert', { expression: 'ctx.value === 42 && res.body.value === 42' }),
      node('fail', 'assert', { expression: 'false', message: 'Expected failure' }), node('end', 'end')), {
      executeRequest: async () => ({ data: { value: 42 } })
    });
    expect(result.nodeResults.assert.status).toBe('success');
    expect(result.error).toBe('Expected failure');
    expect(result.executedNodes).not.toContain('end');
  });
  test('collect-all assertions continue but leave the run failed', async () => {
    const result = await run(linear(node('assert', 'assert', { failFast: false,
      assertions: [{ expression: 'false' }, { expression: 'true' }, { expression: 'ctx.missing.x' }] }), node('end', 'end')));
    expect(result.status).toBe('failed');
    expect(result.nodeResults.assert.assertions).toHaveLength(3);
    expect(result.executedNodes).toContain('end');
  });
  test('tight loops yield to cancellation', async () => {
    const controller = new AbortController();
    setImmediate(() => controller.abort());
    expect((await run(loop('while', { expression: 'true' }), { abortSignal: controller.signal })).status).toBe('cancelled');
  });
  test('rejects broken edges and unstructured cycles', () => {
    const engine = new Engine();
    expect(() => engine.validateGraph({ nodes: [node('a', 'script')], edges: [edge('a', 'missing')] })).toThrow('missing node');
    expect(() => engine.validateGraph({ nodes: [node('a', 'script'), node('b', 'script')], edges: [edge('a', 'b'), edge('b', 'a')] })).toThrow('Cycle');
  });
  test('script execution is bounded', async () => {
    expect((await run(linear(node('script', 'script', { code: 'while (true) {}' })))).error).toMatch('timed out');
  });
});
describe('Flow file resources', () => {
  test('CSV supports quotes, commas, and newlines', () => {
    expect(parseDataset('name,note\r\n"A, B","line 1\nline ""2"""\r\n', 'csv')).toEqual([{ name: 'A, B', note: 'line 1\nline "2"' }]);
  });
  test.each(['name,name\na,b', 'name,note\na', 'name\n"unclosed', 'name\n"closed"junk'])('rejects malformed CSV: %s', (csv) => {
    expect(() => parseDataset(csv, 'csv')).toThrow();
  });
  test('JSON must contain an array', () => { expect(() => parseDataset('{}', 'json')).toThrow('array'); });
  test('rejects traversal and symlink escapes', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'bruno-flow-'));
    try {
      const collection = path.join(dir, 'collection');
      await fs.mkdir(collection);
      await fs.writeFile(path.join(dir, 'outside.json'), '[]');
      await fs.writeFile(path.join(collection, 'inside.json'), '[]');
      await fs.symlink(path.join(dir, 'outside.json'), path.join(collection, 'link.json'));
      await expect(resolveCollectionFile(collection, 'inside.json')).resolves.toBe(path.join(collection, 'inside.json'));
      await expect(resolveCollectionFile(collection, '../outside.json')).rejects.toThrow('escapes');
      await expect(resolveCollectionFile(collection, 'link.json')).rejects.toThrow('escapes');
    } finally { await fs.rm(dir, { recursive: true, force: true }); }
  });
});

describe('Phase 4 persistence and schema assertions', () => {
  test('reads a real serialized child flow and executes it', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'bruno-child-'));
    try {
      const { jsonToBruV2 } = require('@usebruno/lang');
      const child = linear(node('script', 'script', { code: 'ctx.loaded = true;' }));
      await fs.writeFile(path.join(dir, 'child.flow.bru'), jsonToBruV2({ meta: { name: 'Child', type: 'flow' }, flow: child.flow }));
      const result = await run(linear(node('child', 'sub-flow', { flowRef: 'child.flow.bru' })), {
        resolveFlow: (reference) => loadFlowFile(dir, reference)
      });
      expect(result.status).toBe('success');
      expect(result.context.loaded).toBe(true);
    } finally {
      await fs.rm(dir, { recursive: true, force: true });
    }
  });
  test('JSON Schema validates response shape and reports failures', async () => {
    const item = linear(node('req', 'request'), node('assert', 'assert', {
      assertions: [], schema: { type: 'object', required: ['id'], properties: { id: { type: 'number' } } }
    }));
    expect((await run(item, { executeRequest: async () => ({ data: { id: 42 } }) })).status).toBe('success');
    const result = await run(item, { executeRequest: async () => ({ data: { id: 'wrong' } }) });
    expect(result.status).toBe('failed');
    expect(result.error).toMatch('number');
  });
});

describe('Flow validation and error continuation', () => {
  test('shared schema accepts every advanced node type', async () => {
    const { flowSchema } = require('@usebruno/schema');
    const nodes = ['for-each', 'while', 'data-source', 'assert', 'sub-flow'].map((type) => node(type, type));
    await expect(flowSchema.validate({ nodes, edges: [] })).resolves.toBeDefined();
  });
  test('a failed loop follows Done when stopOnError is disabled', async () => {
    const item = loop('while', { expression: 'true', maxIterations: 1 });
    item.flow.config = { stopOnError: false };
    const result = await run(item);
    expect(result.status).toBe('failed');
    expect(result.executedNodes).toEqual(['start', 'loop', 'work', 'loop', 'end']);
  });
});

it('loads YAML sub-flows from saved collection files', async () => {
  const { stringifyRequest } = require('@usebruno/filestore');
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'bruno-yaml-child-'));
  try {
    const child = linear(node('script', 'script', { code: 'ctx.loadedYaml = true;' }));
    await fs.writeFile(path.join(dir, 'child.yml'), stringifyRequest({ type: 'flow', name: 'Child', flow: child.flow }, { format: 'yml' }));
    const result = await run(linear(node('child', 'sub-flow', { flowRef: 'child.yml' })), {
      resolveFlow: (reference) => loadFlowFile(dir, reference)
    });
    expect(result.status).toBe('success');
    expect(result.context.loadedYaml).toBe(true);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
});
