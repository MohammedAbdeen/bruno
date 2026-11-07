const Engine = require('./flow-execution-engine');
const sample = require('../../../../../examples/flows/sum-flow.json');

test('captures immutable input/output for every loop execution', async () => {
  const result = await new Engine().run({ flowItem: sample });
  const additions = result.steps.filter((step) => step.nodeId === 'add');
  expect(additions.map((step) => step.input.context.total)).toEqual([0, 10, 30]);
  expect(additions.map((step) => step.output.context.total)).toEqual([10, 30, 60]);
  expect(new Set(result.steps.map((step) => step.stepId)).size).toBe(result.steps.length);
  expect(result.context.total).toBe(60);
});
test('records request details and normalized response with separate before/after context', async () => {
  const events = [];
  const result = await new Engine().run({ flowItem: { flow: { nodes: [{ id: 'request', type: 'request', data: { outputVars: { token: 'res.body.token' } } }], edges: [] } },
    onEvent: (type, data) => events.push({ type, data }),
    executeRequest: async () => ({ status: 200, data: { token: 'abc' }, requestSent: { method: 'GET', url: 'http://local.test' } }) });
  const step = result.steps[0];
  expect(step.input.context.token).toBeUndefined();
  expect(step.input.request.method).toBe('GET');
  expect(step.output.context.token).toBe('abc');
  expect(step.output.response.body.token).toBe('abc');
  expect(events.find((event) => event.type === 'flow:node-started').data.input.context).toEqual({});
});
test('captures partial changes and failure details when a script throws', async () => {
  const result = await new Engine().run({ flowItem: { flow: { nodes: [{ id: 'script', type: 'script', data: { code: 'ctx.changed = true; throw new Error("Failed step");' } }], edges: [] } } });
  expect(result.steps[0].input.context).toEqual({});
  expect(result.steps[0].output.context.changed).toBe(true);
  expect(result.steps[0].output.details.error).toBe('Failed step');
});
