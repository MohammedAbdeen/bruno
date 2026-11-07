import { duplicateFlowNodes, layoutFlowNodes } from './graph-editing';
import { FLOW_TEMPLATES, createFlowTemplate } from './starter-templates';
const { serialize, deserialize } = require('node:v8');
global.structuredClone = (value) => deserialize(serialize(value));
Object.defineProperty(global, 'crypto', { value: require('node:crypto').webcrypto, configurable: true });
const { validateFlow } = require('@usebruno/js/src/flow/validate-flow');

test('duplicates configuration independently and clears execution state', () => {
  const original = { id: 'a', type: 'assert', position: { x: 10, y: 20 }, data: { assertions: [{ expression: 'true' }], runStatus: 'failed' } };
  const [copy] = duplicateFlowNodes([original], ['a']);
  expect(copy.id).not.toBe('a');
  expect(copy.position).toEqual({ x: 50, y: 60 });
  expect(copy.data.runStatus).toBeUndefined();
  copy.data.assertions[0].expression = 'false';
  expect(original.data.assertions[0].expression).toBe('true');
});
test.each(FLOW_TEMPLATES.map((template) => [template.id]))('layouts the %s template without overlapping nodes', (id) => {
  const flow = createFlowTemplate(id, { login: 'login.bru', api: 'api.bru', submit: 'submit.bru', poll: 'poll.bru', result: 'result.bru', process: 'process.bru' });
  const result = layoutFlowNodes(flow.nodes, flow.edges);
  expect(validateFlow(flow).valid).toBe(true);
  for (let i = 0; i < result.length; i++) for (let j = i + 1; j < result.length; j++) {
    expect(Math.abs(result[i].position.x - result[j].position.x) >= 260 || Math.abs(result[i].position.y - result[j].position.y) >= 125).toBe(true);
  }
  expect(flow.nodes[0].position).toEqual({ x: 0, y: 100 });
});
