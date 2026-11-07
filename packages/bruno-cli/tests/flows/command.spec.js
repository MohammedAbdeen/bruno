const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { promisify } = require('node:util');
const execFile = promisify(require('node:child_process').execFile);
const { jsonToBruV2 } = require('@usebruno/lang');
const cli = path.resolve(__dirname, '../../bin/bru.js');

// Exercise the public entry point, real collection/environment loading, and HTTP runner.
describe('bru run --flow command', () => {
  let dir;
  let server;
  let received;
  let payloads;
  beforeAll(async () => {
    dir = await fs.mkdtemp(path.join(os.tmpdir(), 'bru-flow-command-'));
    received = [];
    payloads = [];
    server = http.createServer((request, response) => {
      received.push(request.url);
      let body = '';
      request.on('data', (chunk) => { body += chunk; });
      request.on('end', () => {
        payloads.push({ headers: request.headers, body });
        response.setHeader('content-type', 'application/json');
        response.end(JSON.stringify({ token: 'local-token' }));
      });
    });
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', resolve);
    });
    await fs.writeFile(path.join(dir, 'bruno.json'), JSON.stringify({ version: '1', name: 'Flows', type: 'collection' }));
    await fs.mkdir(path.join(dir, 'environments'));
    await fs.writeFile(path.join(dir, 'environments/staging.bru'), `vars {\n  baseUrl: http://127.0.0.1:${server.address().port}\n  user: from-env\n}\n`);
    await fs.writeFile(path.join(dir, 'request.bru'), `meta {\n  name: Login\n  type: http\n  seq: 1\n}\n\nget {\n  url: {{baseUrl}}/login?user={{user}}&ctx={{flowValue}}\n  body: none\n  auth: none\n}\n\nscript:post-response {\n  bru.setVar('flowScriptValue', 'from-script');\n}\n\ntests {\n  test("status", function() { expect(res.status).to.equal(200); });\n}\n`);
    const flow = {
      nodes: [{ id: 'start', type: 'start', data: { initialVars: { flowValue: 'from-flow' } } },
        { id: 'request', type: 'request', data: { itemRef: 'request.bru', outputVars: { token: 'res.body.token' } } },
        { id: 'assert', type: 'assert', data: { expression: 'ctx.token === "local-token" && ctx.flowScriptValue === "from-script";' } }],
      edges: [{ id: 'e1', source: 'start', target: 'request' }, { id: 'e2', source: 'request', target: 'assert' }]
    };
    await fs.writeFile(path.join(dir, 'test.flow.bru'), jsonToBruV2({ meta: { name: 'CLI integration', type: 'flow' }, flow }));
    await fs.writeFile(path.join(dir, 'failure.flow.bru'), jsonToBruV2({ meta: { name: 'Failure', type: 'flow' },
      flow: { nodes: [{ id: 'assert', type: 'assert', data: { expression: 'false', message: 'Expected <failure> & report' } }], edges: [] } }));
  });
  afterAll(async () => {
    if (server) await new Promise((resolve) => server.close(resolve));
    if (dir) await fs.rm(dir, { recursive: true, force: true });
  });
  const command = (...args) => execFile(process.execPath, [cli, 'run', ...args, '--noproxy', '--disable-cookies'], {
    cwd: dir, timeout: 20000, env: { ...process.env, NO_COLOR: '1' }
  });
  test('executes HTTP with environment overrides and writes JSON/JUnit reports', async () => {
    const { stdout } = await command('--flow', 'test.flow.bru', '--env', 'staging', '--env-var', 'user=override',
      '--reporter-json', 'results.json', '--reporter-junit', 'results.xml');
    expect(stdout).toContain('Flow success');
    expect(received).toContain('/login?user=override&ctx=from-flow');
    expect(JSON.parse(await fs.readFile(path.join(dir, 'results.json'), 'utf8')).summary.context.token).toBe('local-token');
    expect(await fs.readFile(path.join(dir, 'results.xml'), 'utf8')).toContain('failures="0"');
  }, 25000);
  test('failed flows exit 1 and produce escaped JUnit failures', async () => {
    await expect(command('--flow', 'failure.flow.bru', '--reporter-junit', 'failure.xml')).rejects.toMatchObject({ code: 1 });
    expect(await fs.readFile(path.join(dir, 'failure.xml'), 'utf8')).toContain('&lt;failure> &amp; report');
  }, 25000);
  test('injects environment and CLI variables before the first flow request', async () => {
    await fs.writeFile(path.join(dir, 'injected.flow.bru'), jsonToBruV2({ meta: { name: 'Injected', type: 'flow' }, flow: {
      nodes: [{ id: 'start', type: 'start', data: { initialVars: { buildId: 'default' } } },
        { id: 'assert', type: 'assert', data: { expression: 'ctx.buildId === "9421" && ctx.secretToken === "ci-value" && ctx.user === "from-env"' } }],
      edges: [{ id: 'edge', source: 'start', target: 'assert' }]
    } }));
    const { stdout } = await command('--flow', 'injected.flow.bru', '--env', 'staging', '--env-var', 'buildId=9421', '--env-var', 'secretToken=ci-value');
    expect(stdout).toContain('Flow success');
  }, 25000);
  test('applies header, query, and body overrides during real request execution', async () => {
    await fs.writeFile(path.join(dir, 'post.bru'), `meta {\n name: Post\n type: http\n seq: 2\n}\npost {\n url: {{baseUrl}}/post?user=original\n body: json\n auth: none\n}\nheaders {\n X-Flow: original\n}\nbody:json {\n {"keep":1,"id":"old"}\n}\n`);
    await fs.writeFile(path.join(dir, 'overrides.flow.bru'), jsonToBruV2({ meta: { name: 'Overrides', type: 'flow' }, flow: {
      nodes: [{ id: 'request', type: 'request', data: { itemRef: 'post.bru', overrides: { params: { user: '{{overrideUser}}' }, headers: { 'X-Flow': 'yes' }, body: { id: '{{overrideUser}}' } } } }], edges: []
    } }));
    const { stdout } = await command('--flow', 'overrides.flow.bru', '--env', 'staging', '--env-var', 'overrideUser=from-override');
    expect(stdout).toContain('Flow success');
    expect(received.some((url) => url.includes('user=from-override'))).toBe(true);
    expect(payloads[payloads.length - 1]).toMatchObject({ headers: { 'x-flow': 'yes' } });
    expect(JSON.parse(payloads[payloads.length - 1].body)).toEqual({ keep: 1, id: 'from-override' });
    expect(await fs.readFile(path.join(dir, 'post.bru'), 'utf8')).toContain('X-Flow: original');
  }, 25000);
  test('ordinary recursive runs skip flow files', async () => {
    const { stdout } = await command('-r', '--env', 'staging');
    expect(stdout).not.toContain('Flow success');
    expect(stdout).not.toContain('Expected <failure>');
  }, 25000);
});
