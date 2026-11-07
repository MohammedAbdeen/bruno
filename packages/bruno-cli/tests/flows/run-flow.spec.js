const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { jsonToBruV2 } = require('@usebruno/lang');
const { runFlowCli } = require('../../src/runner/run-flow');

const node = (id, type, data = {}) => ({ id, type, data });
const flow = (nodes) => ({ nodes, edges: nodes.slice(1).map((n, i) => ({ id: `e${i}`, source: nodes[i].id, target: n.id })) });

describe('CLI flow execution and reports', () => {
  let dir;
  let collection;
  const save = async (filename, data) => fs.writeFile(path.join(dir, filename), jsonToBruV2({ meta: { name: 'CLI Test', type: 'flow' }, flow: data }));
  beforeEach(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'bru-cli-flow-'));
    await fs.writeFile(path.join(dir, 'request.bru'), '');
    collection = { pathname: dir, format: 'bru', items: [{ pathname: path.join(dir, 'request.bru'), type: 'http-request', name: 'Request' }] };
  });
  afterEach(async () => { await fs.rm(dir, { recursive: true, force: true }); });

  test('executes collection requests, passes context, extracts outputs and writes reports', async () => {
    await save('test.flow.bru', flow([node('start', 'start', { initialVars: { user: 'Alice' } }),
      node('request', 'request', { itemRef: 'request.bru', outputVars: { token: 'res.body.token' } }),
      node('check', 'assert', { expression: 'ctx.token === "abc"' })]));
    const executeItem = jest.fn(async () => ({ response: { status: 200, data: { token: 'abc' }, headers: { authorization: 'secret', public: 'ok' } } }));
    const log = jest.fn();
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, executeItem, log,
      formats: { json: path.join(dir, 'out.json'), junit: path.join(dir, 'out.xml') }, reporterOptions: { skipHeaders: ['Authorization'], skipResponseBody: true } });
    expect(result.status).toBe('success');
    expect(executeItem.mock.calls[0][1].user).toBe('Alice');
    const report = JSON.parse(await fs.readFile(path.join(dir, 'out.json'), 'utf8'));
    expect(report.summary.context.token).toBe('abc');
    expect(report.summary.steps[1].response.body).toBeUndefined();
    expect(report.summary.nodeResults.request.response.headers).toEqual({ public: 'ok' });
    expect(report.requests[0].response.data).toBeUndefined();
    expect(report.summary.steps[1].output.response.body).toBeUndefined();
    expect(report.summary.steps[1].output.response.headers).toEqual({ public: 'ok' });
    expect(report.summary.steps[2].input.response.body).toBeUndefined();
    expect(report.summary.steps[2].input.response.headers).toEqual({ public: 'ok' });
    expect(await fs.readFile(path.join(dir, 'out.xml'), 'utf8')).toContain('failures="0"');
    expect(log).toHaveBeenCalledWith(expect.stringContaining('Flow success'));
  });
  test('supports existing desktop request paths inside the collection', async () => {
    await save('test.flow.bru', flow([node('request', 'request', { itemRef: path.join(dir, 'request.bru') })]));
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log: () => {},
      executeItem: async () => ({ response: { status: 200 } }) });
    expect(result.status).toBe('success');
  });
  test('request scripts can stop execution even on the last node', async () => {
    await save('test.flow.bru', flow([node('request', 'request', { itemRef: 'request.bru' })]));
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log: () => {},
      executeItem: async () => ({ response: { status: 200 }, shouldStopRunnerExecution: true }) });
    expect(result.status).toBe('cancelled');
  });
  test('runs a YAML flow using the provided Sum Example', async () => {
    const { stringifyRequest } = require('@usebruno/filestore');
    const example = require('../../../../examples/flows/sum-flow.json');
    await fs.writeFile(path.join(dir, 'sum.yml'), stringifyRequest({ type: 'flow', name: example.name, flow: example.flow }, { format: 'yml' }));
    const result = await runFlowCli({ flowPath: 'sum.yml', collection: { ...collection, format: 'yml' }, log: () => {} });
    expect(result.status).toBe('success');
    expect(result.context.total).toBe(60);
  });
  test('request test failures fail the flow and JUnit report', async () => {
    await save('test.flow.bru', flow([node('request', 'request', { itemRef: 'request' })]));
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log: () => {}, formats: { junit: path.join(dir, 'out.xml') },
      executeItem: async () => ({ response: { status: 200 }, testResults: [{ status: 'fail' }] }) });
    expect(result.status).toBe('failed');
    expect(await fs.readFile(path.join(dir, 'out.xml'), 'utf8')).toContain('failures="1"');
  });
  test('nested sub-flow and dataset execute and report every iteration', async () => {
    await save('child.flow.bru', flow([node('assert', 'assert', { expression: 'ctx.row.id > 0' })]));
    await fs.writeFile(path.join(dir, 'data.json'), '[{"id":1},{"id":2}]');
    await save('test.flow.bru', { nodes: [node('start', 'start'), node('data', 'data-source', { fileRef: 'data.json' }),
      node('child', 'sub-flow', { flowRef: 'child.flow.bru' }), node('end', 'end')],
    edges: [{ id: 'e1', source: 'start', target: 'data' }, { id: 'e2', source: 'data', target: 'child', sourceHandle: 'body' },
      { id: 'e3', source: 'child', target: 'data' }, { id: 'e4', source: 'data', target: 'end', sourceHandle: 'done' }] });
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log: () => {}, formats: { junit: path.join(dir, 'out.xml') } });
    expect(result.status).toBe('success');
    const xml = await fs.readFile(path.join(dir, 'out.xml'), 'utf8');
    expect(xml.match(/classname="Bruno.Flow"/g)).toHaveLength(9);
    expect(xml).toContain('/assert');
  });
  test('invalid graph still writes a failed CI report', async () => {
    await save('test.flow.bru', { nodes: [], edges: [] });
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log: () => {}, formats: { junit: path.join(dir, 'out.xml') } });
    expect(result.status).toBe('failed');
    expect(await fs.readFile(path.join(dir, 'out.xml'), 'utf8')).toContain('failures="1"');
  });
  test('rejects unsupported report formats before executing', async () => {
    await expect(runFlowCli({ flowPath: 'test.flow.bru', collection, formats: { html: path.join(dir, 'out.html') } })).rejects.toThrow('JSON and JUnit');
  });
  test('SIGINT cancels delays, writes reports, and restores signal listeners', async () => {
    await save('test.flow.bru', flow([node('delay', 'delay', { durationMs: 10000 })]));
    const count = process.listenerCount('SIGINT');
    const log = (text) => { if (text.includes('Running flow:')) setTimeout(() => process.emit('SIGINT'), 10); };
    const result = await runFlowCli({ flowPath: 'test.flow.bru', collection, log, formats: { json: path.join(dir, 'out.json') } });
    expect(result.status).toBe('cancelled');
    expect(process.listenerCount('SIGINT')).toBe(count);
    expect(JSON.parse(await fs.readFile(path.join(dir, 'out.json'), 'utf8')).summary.status).toBe('cancelled');
  });
});
