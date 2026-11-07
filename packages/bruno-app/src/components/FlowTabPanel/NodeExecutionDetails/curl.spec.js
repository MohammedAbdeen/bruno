import { capturedRequestToCurl } from './curl';

test('uses captured method, query, headers and payload', () => {
  const curl = capturedRequestToCurl({ method: 'POST', url: 'https://example.com/test?a=one&a=two', headers: { 'Content-Type': 'application/json', 'X-Trace': 'captured' }, data: { id: 42 } });
  expect(curl).toContain('POST');
  expect(curl).toContain('a=one');
  expect(curl).toContain('a=two');
  expect(curl).toContain('X-Trace: captured');
  expect(curl).toContain('"id":42');
});
test('does not copy a redacted file payload as if it were the original', () => {
  expect(() => capturedRequestToCurl({ url: 'https://example.com/upload', data: '<request body redacted>' })).toThrow('cannot be reconstructed');
});
