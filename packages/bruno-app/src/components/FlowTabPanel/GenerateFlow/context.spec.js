import { buildFlowRequests } from './context';
jest.mock('utils/collections', () => ({ flattenItems: (items) => items }));

test('sends request variable names without header, body, or URL values', () => {
  const requests = buildFlowRequests({ items: [{ pathname: '/collection/login.bru', name: 'Login', type: 'http-request',
    request: { auth: { token: 'AUTH_SECRET', variable: '{{authToken}}' }, method: 'POST', url: 'https://private.example/{{tenant}}', headers: [{ name: 'Authorization', value: 'SECRET {{token}}' }],
      body: { json: '{"password":"TOP_SECRET", "user":"{{ userId }}"}' } } },
  { type: 'flow', pathname: '/collection/flow.bru' },
  { type: 'http-request', pathname: '/collection/transient.bru', isTransient: true }] });
  expect(requests).toHaveLength(1);
  expect(requests[0].inputs).toEqual(expect.arrayContaining(['tenant', 'token', 'userId', 'authToken']));
  expect(JSON.stringify(requests)).not.toContain('SECRET');
  expect(JSON.stringify(requests)).not.toContain('private.example');
});
