const cloneDeep = require('lodash/cloneDeep');
const isPlainObject = require('lodash/isPlainObject');

function validateRequestOverrides(overrides = {}) {
  if (!isPlainObject(overrides)) throw new Error('Overrides must be an object');
  for (const [key, value] of Object.entries(overrides)) {
    if (!['headers', 'params', 'body'].includes(key)) throw new Error(`Unsupported request override: ${key}`);
    if (!isPlainObject(value)) throw new Error(`Override ${key} must be a JSON object`);
    for (const name of Object.keys(value)) {
      if (['__proto__', 'prototype', 'constructor'].includes(name)) throw new Error('Invalid override key');
      if (key !== 'body' && (typeof value[name] !== 'string' && typeof value[name] !== 'number' && typeof value[name] !== 'boolean')) throw new Error(`Override ${key} values must be strings, numbers, or booleans`);
    }
  }
}

// Apply to a private copy before normal interpolation and request scripts.
// Params are query parameters; object body overrides shallow-merge JSON bodies.
function applyRequestOverrides(item, overrides = {}) {
  validateRequestOverrides(overrides);
  const result = cloneDeep(item);
  if (!Object.keys(overrides).length) return result;
  const request = result.draft?.request || result.request;
  if (!request) throw new Error('Request configuration is missing');
  for (const [name, value] of Object.entries(overrides.headers || {})) {
    request.headers = (request.headers || []).filter((header) => header.name.toLowerCase() !== name.toLowerCase());
    request.headers.push({ name, value: String(value), enabled: true });
    // An explicit Authorization header is the authentication for this step;
    // inherited/saved auth must not replace it during preparation.
    if (name.toLowerCase() === 'authorization') request.auth = { mode: 'none' };
  }
  if (Object.keys(overrides.params || {}).length) {
    // Preserve templated base URLs and untouched query segments verbatim.
    const [url, fragment] = request.url.split('#');
    const queryAt = url.indexOf('?');
    const base = queryAt < 0 ? url : url.slice(0, queryAt);
    let parts = queryAt < 0 ? [] : url.slice(queryAt + 1).split('&').filter(Boolean);
    for (const [name, value] of Object.entries(overrides.params)) {
      parts = parts.filter((part) => {
        try { return decodeURIComponent(part.split('=')[0].replace(/\+/g, ' ')) !== name; } catch { return true; }
      });
      // Values are interpolated later, so preserve {{variable}} references.
      const encode = (value) => encodeURIComponent(String(value)).replace(/%7B/g, '{').replace(/%7D/g, '}');
      parts.push(`${encode(name)}=${encode(value)}`);
      request.params = (request.params || []).filter((param) => param.type !== 'query' || param.name !== name);
      request.params.push({ name, value: String(value), type: 'query', enabled: true });
    }
    request.url = `${base}?${parts.join('&')}${fragment === undefined ? '' : `#${fragment}`}`;
  }
  if (overrides.body !== undefined) {
    const body = request.body || { mode: 'none' };
    if (!['none', 'json'].includes(body.mode)) throw new Error('Body overrides require a JSON request body');
    let original = {};
    if (body.mode === 'json' && body.json?.trim()) {
      try { original = JSON.parse(body.json); } catch { throw new Error('Body overrides require a valid JSON object body'); }
      if (!isPlainObject(original)) throw new Error('Body overrides require a JSON object body');
    }
    request.body = { ...body, mode: 'json', json: JSON.stringify({ ...original, ...overrides.body }) };
  }
  return result;
}

// Header inheritance reads the request leaf from the collection tree. Replace
// that leaf in a private tree so it sees the effective configuration as well.
function collectionWithFlowRequest(collection, item) {
  const walk = (items) => (items || []).map((entry) => {
    if ((item.uid && entry.uid === item.uid) || (item.pathname && entry.pathname === item.pathname)) return item;
    return entry.items ? { ...entry, items: walk(entry.items) } : entry;
  });
  return { ...collection, items: walk(collection.items) };
}
module.exports = { applyRequestOverrides, validateRequestOverrides, collectionWithFlowRequest };
