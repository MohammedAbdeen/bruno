const { applyRequestOverrides, validateRequestOverrides } = require('@usebruno/js/src/flow/request-overrides');

test('overrides a private request copy, preserving templates and unrelated fields', () => {
  const item = { request: { url: '{{baseUrl}}/users?limit=1&keep=yes#part', headers: [{ name: 'X-Test', value: 'old', enabled: true }],
    params: [{ name: 'id', value: '42', type: 'path' }], body: { mode: 'json', json: '{"keep":1,"nested":{"old":true}}' } } };
  const result = applyRequestOverrides(item, { headers: { 'x-test': '{{token}}' }, params: { limit: 5, filter: '{{filter}}' }, body: { nested: { new: true }, id: '{{id}}' } });
  expect(result.request.url).toBe('{{baseUrl}}/users?keep=yes&limit=5&filter={{filter}}#part');
  expect(result.request.headers).toEqual([{ name: 'x-test', value: '{{token}}', enabled: true }]);
  expect(result.request.params[0].type).toBe('path');
  expect(JSON.parse(result.request.body.json)).toEqual({ keep: 1, nested: { new: true }, id: '{{id}}' });
  expect(item.request.headers[0].value).toBe('old');
  expect(item.request.url).toContain('limit=1');
});
test('validates unsupported fields and incompatible bodies', () => {
  expect(() => validateRequestOverrides({ url: 'elsewhere' })).toThrow('Unsupported');
  expect(() => validateRequestOverrides({ params: [] })).toThrow('JSON object');
  expect(() => applyRequestOverrides({ request: { body: { mode: 'text', text: 'abc' } } }, { body: {} })).toThrow('JSON');
  expect(() => validateRequestOverrides(JSON.parse('{"headers":{"__proto__":"bad"}}'))).toThrow('Invalid');
});
