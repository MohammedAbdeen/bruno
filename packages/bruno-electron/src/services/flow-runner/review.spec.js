const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const Engine = require('./flow-execution-engine');
const { compileFlowExpression } = require('@usebruno/js/src/flow/expressions');
const { validateFlow } = require('@usebruno/js/src/flow/validate-flow');
const { FlowDebugController } = require('@usebruno/js/src/flow/debug-controller');
const { resolveCollectionFile } = require('./flow-resources');
const sample = require('../../../../../examples/flows/sum-flow.json');

const linear = (...nodes) => ({ flow: { nodes, edges: nodes.slice(1).map((node, index) => ({ id: `e${index}`, source: nodes[index].id, target: node.id })) } });

describe('Flow review regressions', () => {
  test.each(['ctx.value === 3;  ; ', 'const body = res.body; body.active;', '{ const value = ctx.value; value === 3; }', 'return ctx.value === 3;'])('evaluates expressions and statement completion: %s', (expression) => {
    expect(compileFlowExpression(expression).runInNewContext({ ctx: { value: 3 }, res: { body: { active: true } } })).toBe(true);
  });
  test('a runtime failure never retries side effects', () => {
    const ctx = { count: 0 };
    expect(() => new Engine().evaluate('ctx.count++; throw new Error("once");', ctx, {})).toThrow('once');
    expect(ctx.count).toBe(1);
  });
  test('bad conditions fail the node rather than silently taking the false branch', async () => {
    const result = await new Engine().run({ flowItem: linear({ id: 'condition', type: 'condition', data: { expression: 'notDefined.value;' } }) });
    expect(result.status).toBe('failed');
    expect(result.error).toContain('Condition evaluation failed');
  });
  test('ignores blank assertions and preserves schema-only checks', async () => {
    const result = await new Engine().run({ flowItem: linear({ id: 'assert', type: 'assert', data: { assertions: [{ expression: ' ' }, { expression: 'true;' }, { expression: '' }] } }) });
    expect(result.status).toBe('success');
    expect(result.steps[0].assertions).toHaveLength(1);
  });
  test('request runtime changes propagate into ctx and explicit output mappings win', async () => {
    const result = await new Engine().run({ flowItem: linear({ id: 'request', type: 'request', data: { outputVars: { token: 'res.body.token' } } },
      { id: 'assert', type: 'assert', data: { expression: 'ctx.scriptValue === 42 && ctx.token === "mapped";' } }),
    executeRequest: async () => ({ status: 200, body: { token: 'mapped' }, updatedVariables: { scriptValue: 42, token: 'script' } }) });
    expect(result.status).toBe('success');
  });
  test('accepts absolute paths inside a symlinked collection and rejects outside files', async () => {
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'flow-path-review-'));
    try {
      const root = path.join(dir, 'real');
      await fs.mkdir(root);
      await fs.writeFile(path.join(root, 'data.json'), '[]');
      await fs.writeFile(path.join(dir, 'outside.json'), '[]');
      await fs.symlink(root, path.join(dir, 'alias'));
      await expect(resolveCollectionFile(path.join(dir, 'alias'), path.join(root, 'data.json'))).resolves.toBe(path.join(root, 'data.json'));
      await expect(resolveCollectionFile(root, path.join(dir, 'outside.json'))).rejects.toThrow('escapes');
    } finally { await fs.rm(dir, { recursive: true, force: true }); }
  });
});

describe('Flow health validation', () => {
  test('accepts a complete loop and semicolon assertions', () => {
    const flow = JSON.parse(JSON.stringify(sample.flow));
    flow.nodes.find((node) => node.id === 'verify').data.expression += ';';
    expect(validateFlow(flow)).toEqual({ valid: true, issues: [] });
  });
  test('finds unreachable nodes, missing loop returns, and undeclared variables', () => {
    const flow = JSON.parse(JSON.stringify(sample.flow));
    flow.edges = flow.edges.filter((edge) => edge.id !== 'e3');
    flow.nodes.push({ id: 'orphan', type: 'assert', data: { expression: 'ctx.unknown === true' } });
    const { issues, valid } = validateFlow(flow);
    expect(valid).toBe(false);
    expect(issues.map((issue) => issue.code)).toEqual(expect.arrayContaining(['loop-return', 'unreachable', 'unmapped-context']));
  });
});

describe('Interactive flow debugging', () => {
  test('pauses, edits context, steps, and continues to completion', async () => {
    const pauses = [];
    const debug = new FlowDebugController({ pauseOnStart: true, onPause: (pause) => {
      pauses.push(pause);
      debug.resume(pauses.length === 1 ? 'step' : 'continue', pauses.length === 1 ? { count: 5 } : undefined);
    } });
    const result = await new Engine().run({ flowItem: linear({ id: 'start', type: 'start' }, { id: 'script', type: 'script', data: { code: 'ctx.count += 1;' } }, { id: 'end', type: 'end' }), beforeStep: (step) => debug.beforeStep(step) });
    expect(pauses.map((pause) => pause.nodeId)).toEqual(['start', 'script']);
    expect(result.context.count).toBe(6);
  });
  test('honors breakpoints on normal runs and supports cancellation while paused', async () => {
    const abort = new AbortController();
    const debug = new FlowDebugController({ onPause: () => abort.abort() });
    const result = await new Engine().run({ flowItem: linear({ id: 'break', type: 'script', data: { breakpoint: true, code: 'ctx.shouldNotRun = true;' } }),
      abortSignal: abort.signal, beforeStep: (step) => debug.beforeStep(step) });
    expect(result.status).toBe('cancelled');
    expect(result.context.shouldNotRun).toBeUndefined();
    expect(debug.pending).toBeNull();
  });
  test('steps through a sub-flow and merges edits back into the parent context', async () => {
    const pauses = [];
    const debug = new FlowDebugController({ pauseOnStart: true, onPause: (pause) => {
      pauses.push(`${pause.flowName}:${pause.nodeId}`);
      debug.resume('step', pause.nodeId === 'child-script' ? { value: 4 } : undefined);
    } });
    const parent = { ...linear({ id: 'start', type: 'start' }, { id: 'child', type: 'sub-flow', data: { flowRef: 'child.flow.bru' } }, { id: 'end', type: 'end' }), name: 'Parent' };
    const child = { ...linear({ id: 'child-script', type: 'script', data: { code: 'ctx.value += 1;' } }), name: 'Child' };
    const result = await new Engine().run({ flowItem: parent, resolveFlow: async () => child, beforeStep: (step) => debug.beforeStep(step) });
    expect(result.status).toBe('success');
    expect(result.context.value).toBe(5);
    expect(pauses).toEqual(['Parent:start', 'Parent:child', 'Child:child-script', 'Parent:end']);
  });
});
