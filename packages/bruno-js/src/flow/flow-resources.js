const fs = require('node:fs/promises');
const path = require('node:path');

// CSV header rows, escaped quotes, CRLF, and quoted multiline fields.
function parseDataset(content, format) {
  content = String(content).replace(/^\uFEFF/, '');
  if (format === 'json') {
    const rows = JSON.parse(content);
    if (!Array.isArray(rows)) throw new Error('JSON dataset must be an array');
    return rows;
  }
  if (format !== 'csv') throw new Error('Dataset format must be csv or json');
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  let closed = false;
  const pushRow = () => {
    row.push(field);
    if (row.some((value) => value !== '')) rows.push(row);
    row = []; field = ''; closed = false;
  };
  for (let i = 0; i < content.length; i++) {
    const ch = content[i];
    if (quoted) {
      if (ch === '"' && content[i + 1] === '"') {
        field += '"'; i++;
      } else if (ch === '"') {
        quoted = false; closed = true;
      } else field += ch;
    } else if (ch === ',') {
      row.push(field); field = ''; closed = false;
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && content[i + 1] === '\n') i++;
      pushRow();
    } else if (ch === '"' && !field && !closed) {
      quoted = true;
    } else {
      if (closed || ch === '"') throw new Error('Invalid CSV quoting');
      field += ch;
    }
  }
  if (quoted) throw new Error('Unterminated CSV quote');
  if (field || row.length || closed) pushRow();
  if (!rows.length) throw new Error('CSV dataset requires a header row');
  const headers = rows.shift();
  if (headers.some((key) => !key.trim()) || new Set(headers).size !== headers.length) {
    throw new Error('CSV headers must be nonempty and unique');
  }
  return rows.map((values) => {
    if (values.length !== headers.length) throw new Error('CSV row does not match header column count');
    return Object.fromEntries(headers.map((key, i) => [key, values[i]]));
  });
}

async function resolveCollectionFile(collectionPath, reference) {
  if (typeof reference !== 'string' || !reference.trim()) {
    throw new Error('File reference must be a non-empty string');
  }
  const root = await fs.realpath(collectionPath);
  const target = await fs.realpath(path.isAbsolute(reference) ? reference : path.resolve(root, reference));
  const relative = path.relative(root, target);
  if (relative.startsWith(`..${path.sep}`) || relative === '..' || path.isAbsolute(relative)) {
    throw new Error('File reference escapes the collection');
  }
  return target;
}
module.exports = { parseDataset, resolveCollectionFile };
