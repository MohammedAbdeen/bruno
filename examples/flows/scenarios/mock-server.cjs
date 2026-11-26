const http = require('node:http');

// Loopback-only demo API. No external service or real credentials are needed.
function createServer() {
  return http.createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      const url = new URL(request.url, 'http://localhost');
      response.setHeader('Content-Type', 'application/json');
      if (url.pathname === '/login') {
        response.end(JSON.stringify({ token: 'local-demo-token' }));
        return;
      }
      if (url.pathname !== '/echo') {
        response.writeHead(404).end(JSON.stringify({ error: 'Not found' }));
        return;
      }
      let parsedBody = null;
      try {
        if (body) parsedBody = JSON.parse(body);
      } catch {
        response.writeHead(400).end(JSON.stringify({ error: 'Invalid JSON' }));
        return;
      }
      response.end(JSON.stringify({
        method: request.method,
        query: Object.fromEntries(url.searchParams),
        headers: request.headers,
        body: parsedBody,
        jobStatus: Number(url.searchParams.get('attempt') || 0) >= 2 ? 'DONE' : 'PENDING'
      }));
    });
  });
}

if (require.main === module) {
  const server = createServer();
  server.on('error', (error) => { console.error(error.message); process.exitCode = 1; });
  server.listen(4010, '127.0.0.1', () => console.log('Demo API: http://127.0.0.1:4010'));
}

module.exports = { createServer };
