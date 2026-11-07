const { buildCatalog, prepareGeneratedFlow, registerFlowGenerationIpc } = require('./flow-generation');
const Engine = require('@usebruno/js/src/flow/flow-execution-engine');
const root = '/collection';
const requests = [
  { pathname: `${root}/login.bru`, name: 'Login', inputs: [], responseData: { token: 'TOP_SECRET', user: { id: 42 } } },
  { pathname: `${root}/inventory.bru`, name: 'Inventory', inputs: ['authToken'], responseData: { items: [{ id: 33 }] } }
];
const node = (id, type, data = {}) => ({ id, type, data });
const linear = () => ({ nodes: [node('start', 'start'), node('login', 'request', { itemRef: 'login.bru' }),
  node('inventory', 'request', { itemRef: 'inventory.bru' }), node('end', 'end')] });
const catalog = () => buildCatalog(requests, root);

describe('AI flow generation', () => {
  test('catalog includes shapes and paths without response values', () => {
    const serialized = JSON.stringify(catalog());
    expect(serialized).not.toContain('TOP_SECRET');
    expect(serialized).not.toContain('42');
    expect(serialized).toContain('res.body.token');
    expect(serialized).toContain('authToken');
  });
  test('rejects collection escapes and empty request catalogs', () => {
    expect(() => buildCatalog([{ pathname: '/outside/request.bru' }], root)).toThrow('inside');
    expect(() => buildCatalog([], root)).toThrow('Add saved');
  });
  test('automatically wires linear nodes and maps downstream variables from known responses', async () => {
    const { flow } = await prepareGeneratedFlow(JSON.stringify(linear()), catalog());
    expect(flow.edges).toHaveLength(3);
    expect(flow.nodes[1].data.outputVars).toEqual({ authToken: 'res.body.token' });
    const executeRequest = jest.fn(async () => ({ status: 200, body: { token: 'abc' } }));
    const result = await new Engine().run({ flowItem: { flow }, executeRequest });
    expect(result.status).toBe('success');
    expect(executeRequest.mock.calls[1][0].context.authToken).toBe('abc');
  });
  test('preserves valid explicit mappings and produces review notices for missing responses', async () => {
    const input = linear();
    input.nodes[1].data.outputVars = { userId: 'res.body.user.id' };
    const known = catalog();
    known[1].responsePaths = [];
    const result = await prepareGeneratedFlow(`\`\`\`json\n${JSON.stringify(input)}\n\`\`\``, known);
    expect(result.flow.nodes[1].data.outputVars.userId).toBe('res.body.user.id');
    expect(result.notices).toHaveLength(1);
  });
  test('rejects invented requests and unsupported overrides', async () => {
    const input = linear();
    input.nodes[1].data.itemRef = 'missing.bru';
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('Unknown');
    input.nodes[1].data.itemRef = 'login.bru';
    input.nodes[1].data.overrides = { url: 'https://elsewhere' };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('unsupported');
  });
  test('rejects nonexistent response mappings', async () => {
    const input = linear();
    input.nodes[1].data.outputVars = { token: 'res.body.missing' };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('Unknown response path');
  });
  test('rejects unsafe mapping names without executing generated scripts', async () => {
    const input = linear();
    input.nodes.splice(1, 0, node('script', 'script', { code: 'throw new Error("Should never execute");' }));
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).resolves.toBeDefined();
    input.nodes[2].data.outputVars = JSON.parse('{"__proto__":"res.body.token"}');
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('Invalid response mapping');
  });
  test('requires explicit condition handles', async () => {
    const input = { nodes: [node('start', 'start'), node('condition', 'condition', { expression: 'true' }), node('end', 'end')] };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('explicit connections');
  });
  test('validates explicit branch wiring', async () => {
    const input = { nodes: [node('start', 'start'), node('condition', 'condition', { expression: 'true' }), node('end', 'end')], edges: [
      { source: 'start', target: 'condition' }, { source: 'condition', target: 'end', sourceHandle: 'true' }, { source: 'condition', target: 'end', sourceHandle: 'false' }
    ] };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).resolves.toBeDefined();
    input.edges.pop();
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('branch connections');
  });
  test.each(['res.status === 200;', 'const body = res.body; body && body.active;', 'return res.status === 200;'])('accepts expression syntax also supported by the runner: %s', async (expression) => {
    const input = { nodes: [node('start', 'start'), node('condition', 'condition', { expression }), node('end', 'end')], edges: [
      { source: 'start', target: 'condition' }, { source: 'condition', target: 'end', sourceHandle: 'true' }, { source: 'condition', target: 'end', sourceHandle: 'false' }
    ] };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).resolves.toBeDefined();
  });
  test('validates loop body returns before accepting the graph', async () => {
    const input = { nodes: [node('start', 'start'), node('loop', 'for-each', { expression: '[1, 2]' }),
      node('script', 'script', { code: 'ctx.visited = true;' }), node('end', 'end')], edges: [
      { source: 'start', target: 'loop' }, { source: 'loop', target: 'script', sourceHandle: 'body' },
      { source: 'script', target: 'loop' }, { source: 'loop', target: 'end', sourceHandle: 'done' }
    ] };
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).resolves.toBeDefined();
    input.edges[2].target = 'end';
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('must return');
  });
  test('rejects disconnected graphs and duplicate nodes', async () => {
    const input = linear();
    input.nodes.push(node('extra', 'end'));
    input.edges = [{ source: 'start', target: 'login' }, { source: 'login', target: 'inventory' }, { source: 'inventory', target: 'end' }];
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('disconnected');
    input.nodes[4].id = 'end';
    await expect(prepareGeneratedFlow(JSON.stringify(input), catalog())).rejects.toThrow('Duplicate');
  });
  test('reports declines and malformed JSON', async () => {
    await expect(prepareGeneratedFlow('{"error":"Add a login request"}', catalog())).rejects.toThrow('Add a login request');
    await expect(prepareGeneratedFlow('not JSON', catalog())).rejects.toThrow();
  });
});

describe('AI flow IPC', () => {
  let handler;
  let dependencies;
  beforeEach(() => {
    dependencies = { ipcMain: { handle: (_channel, fn) => { handler = fn; } },
      generateText: jest.fn(async () => ({ text: JSON.stringify(linear()) })),
      resolveModel: jest.fn(() => 'configured-model'), pickDefaultModelId: () => 'default-model', activeStreams: new Map() };
    registerFlowGenerationIpc(dependencies);
  });
  const params = () => ({ prompt: 'Log in and get inventory', collectionPath: root, requests, streamId: 'generation-1' });
  test('uses the configured model and sends a redacted catalog', async () => {
    const result = await handler({}, params());
    expect(result.flow.nodes).toHaveLength(4);
    expect(dependencies.resolveModel).toHaveBeenCalledWith('default-model');
    expect(dependencies.generateText.mock.calls[0][0].prompt).not.toContain('TOP_SECRET');
    expect(dependencies.activeStreams.size).toBe(0);
  });
  test('disabled AI and unavailable providers surface errors without calling generation', async () => {
    dependencies.resolveModel.mockImplementation(() => { throw new Error('AI disabled'); });
    expect(await handler({}, params())).toEqual({ error: 'AI disabled' });
    expect(dependencies.generateText).not.toHaveBeenCalled();
  });
  test('cancellation discards late results and cleans up', async () => {
    dependencies.generateText.mockImplementation(async () => {
      dependencies.activeStreams.get('generation-1').abort();
      return { text: JSON.stringify(linear()) };
    });
    expect(await handler({}, params())).toEqual({ stopped: true });
    expect(dependencies.activeStreams.size).toBe(0);
  });
  test('provider errors and invalid prompts leave no active generation', async () => {
    dependencies.generateText.mockRejectedValue(new Error('Provider unavailable'));
    expect(await handler({}, params())).toEqual({ error: 'Provider unavailable' });
    expect((await handler({}, { ...params(), prompt: '' })).error).toContain('description');
    expect(dependencies.activeStreams.size).toBe(0);
  });
});
