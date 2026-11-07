// Use the same HTTPSnippet cURL generator as Generate Code, with the captured
// transport values rather than the saved request or current environment.
export const capturedRequestToCurl = (request) => {
  const { HTTPSnippet } = require('httpsnippet');
  const url = new URL(request.url);
  const headers = Object.entries(request.headers || {}).filter(([, value]) => value !== false && value !== undefined)
    .map(([name, value]) => ({ name, value: String(value) }));
  const contentType = headers.find((header) => header.name.toLowerCase() === 'content-type')?.value || 'application/octet-stream';
  const data = request.data;
  if ((data === undefined && request.dataBuffer) || data === '<request body redacted>' || contentType.toLowerCase().startsWith('multipart/form-data')) throw new Error('This binary or multipart request cannot be reconstructed from its text capture.');
  const queryString = [...url.searchParams].map(([name, value]) => ({ name, value }));
  url.search = ''; url.hash = '';
  const har = { method: (request.method || 'GET').toUpperCase(), url: url.toString(),
    httpVersion: 'HTTP/1.1', headers, queryString,
    cookies: [], headersSize: -1, bodySize: -1,
    ...(data !== undefined && data !== null ? { postData: { mimeType: contentType, text: typeof data === 'string' ? data : JSON.stringify(data) } } : {}) };
  return new HTTPSnippet(har).convert('shell', 'curl');
};
