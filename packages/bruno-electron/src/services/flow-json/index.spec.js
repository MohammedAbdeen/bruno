const { normalizeFlowJson } = require('./index');
const { registerFlowJsonIpc } = require('../../ipc/flow-json');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const graph = () => ({ nodes: [{ id: 'start', type: 'start', data: { initialVars: { count: 2 }, runStatus: 'success' }, selected: true },
  { id: 'request', type: 'request', data: { itemRef: 'request.bru', outputVars: { token: 'res.body.token' }, response: { body: 'result' } } }],
edges: [{ id: 'edge', source: 'start', target: 'request', selected: true }], config: { stopOnError: false } });
const envelope = (flow) => ({ type: 'bruno-flow', version: 1, name: 'Example', flow });

describe('Flow JSON format', () => {
  test('round-trips graph settings and mappings while excluding runtime/UI state', async () => {
    const output = await normalizeFlowJson(envelope(graph()));
    expect(output.flow.nodes[0]).not.toHaveProperty('selected');
    expect(output.flow.nodes[0].data).not.toHaveProperty('runStatus');
    expect(output.flow.nodes[1].data).not.toHaveProperty('response');
    expect(output.flow.nodes[1].data.outputVars.token).toBe('res.body.token');
    expect(output.flow.config.stopOnError).toBe(false);
    expect(await normalizeFlowJson(JSON.parse(JSON.stringify(output)))).toEqual(output);
  });
  test('accepts raw graph JSON and incomplete drafts', async () => {
    expect((await normalizeFlowJson({ nodes: [], edges: [] })).name).toBe('Imported Flow');
    expect((await normalizeFlowJson(graph())).flow.nodes).toHaveLength(2);
  });
  test('normalizes local absolute references and rejects collection escapes', async () => {
    const root = path.join(os.tmpdir(), 'collection');
    const flow = graph();
    flow.nodes[1].data.itemRef = path.join(root, 'auth', 'login.bru');
    expect((await normalizeFlowJson(flow, root)).flow.nodes[1].data.itemRef).toBe('auth/login.bru');
    flow.nodes[1].data.itemRef = '../outside.bru';
    await expect(normalizeFlowJson(flow, root)).rejects.toThrow('inside');
  });
  test('rejects unsupported versions, invalid types, duplicates, and broken connections', async () => {
    await expect(normalizeFlowJson({ ...envelope(graph()), version: 2 })).rejects.toThrow('version');
    await expect(normalizeFlowJson({})).rejects.toThrow('arrays');
    const flow = graph();
    flow.nodes[1].type = 'invented';
    await expect(normalizeFlowJson(flow)).rejects.toThrow();
    flow.nodes[1].type = 'request';
    flow.nodes[1].id = 'start';
    await expect(normalizeFlowJson(flow)).rejects.toThrow('Duplicate node');
    flow.nodes[1].id = 'request';
    flow.edges[0].target = 'missing';
    await expect(normalizeFlowJson(flow)).rejects.toThrow('missing node');
  });
});

describe('Flow JSON file dialogs', () => {
  let dir;
  let handlers;
  let dialog;
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'flow-json-'));
    handlers = {};
    dialog = { showOpenDialog: jest.fn(), showSaveDialog: jest.fn() };
    registerFlowJsonIpc({ ipcMain: { handle: (channel, fn) => { handlers[channel] = fn; } }, dialog });
  });
  afterEach(async () => { await fs.rm(dir, { recursive: true, force: true }); });
  test('exports and imports a JSON file', async () => {
    const filename = path.join(dir, 'example.json');
    dialog.showSaveDialog.mockResolvedValue({ filePath: filename });
    await handlers['renderer:export-flow-json']({}, { name: 'Example', flow: graph() });
    expect(JSON.parse(await fs.readFile(filename, 'utf8')).version).toBe(1);
    dialog.showOpenDialog.mockResolvedValue({ filePaths: [filename] });
    const imported = await handlers['renderer:import-flow-json']({}, {});
    expect(imported.name).toBe('Example');
    expect(imported.flow.nodes).toHaveLength(2);
  });
  test('cancellation does not write or parse a file', async () => {
    dialog.showSaveDialog.mockResolvedValue({ canceled: true });
    dialog.showOpenDialog.mockResolvedValue({ canceled: true });
    expect(await handlers['renderer:export-flow-json']({}, { flow: graph() })).toEqual({ canceled: true });
    expect(await handlers['renderer:import-flow-json']({}, {})).toEqual({ canceled: true });
    expect(await fs.readdir(dir)).toEqual([]);
  });
  test('malformed JSON is rejected', async () => {
    const filename = path.join(dir, 'invalid.json');
    await fs.writeFile(filename, '{broken');
    dialog.showOpenDialog.mockResolvedValue({ filePaths: [filename] });
    await expect(handlers['renderer:import-flow-json']({}, {})).rejects.toThrow();
  });
});
