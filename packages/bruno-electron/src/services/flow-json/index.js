const path = require('node:path');
const { flowSchema } = require('@usebruno/schema');
const { cloneDeep, pick } = require('lodash');
const { validateName } = require('@usebruno/common').utils;

async function normalizeFlowJson(input, collectionPath) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Expected a flow JSON object');
  const wrapped = Object.hasOwn(input, 'flow');
  if (wrapped && (input.type !== 'bruno-flow' || input.version !== 1)) throw new Error('Unsupported flow JSON format or version');
  const source = wrapped ? input.flow : input;
  if (!source || !Array.isArray(source.nodes) || !Array.isArray(source.edges)) throw new Error('Flow JSON requires nodes and edges arrays');
  await flowSchema.validate(source, { strict: true });
  const ids = new Set(source.nodes.map((node) => node.id));
  if (ids.size !== source.nodes.length) throw new Error('Duplicate node IDs');
  if (new Set(source.edges.map((edge) => edge.id)).size !== source.edges.length) throw new Error('Duplicate edge IDs');
  for (const edge of source.edges) {
    if (!ids.has(edge.source) || !ids.has(edge.target)) throw new Error('Connection references a missing node');
  }
  const flow = {
    nodes: source.nodes.map((node) => {
      const data = cloneDeep(node.data || {});
      for (const key of ['runStatus', 'conditionMet', 'response']) delete data[key];
      for (const key of ['itemRef', 'flowRef', 'fileRef']) {
        if (typeof data[key] !== 'string' || !data[key]) continue;
        let reference = data[key];
        if (path.isAbsolute(reference)) {
          if (!collectionPath) throw new Error('Absolute references require a collection path');
          reference = path.relative(collectionPath, reference);
        }
        reference = path.normalize(reference);
        if (reference === '..' || reference.startsWith(`..${path.sep}`) || path.isAbsolute(reference)) throw new Error('File reference must stay inside the collection');
        data[key] = reference.split(path.sep).join('/');
      }
      return { ...pick(node, ['id', 'type', 'position']), data };
    }),
    edges: source.edges.map((edge) => pick(edge, ['id', 'source', 'target', 'sourceHandle', 'targetHandle', 'label'])),
    viewport: cloneDeep(source.viewport || { x: 0, y: 0, zoom: 1 }),
    config: cloneDeep(source.config || {})
  };
  const name = wrapped && typeof input.name === 'string' && input.name.trim() ? input.name.trim() : 'Imported Flow';
  if (name.length > 255 || !validateName(name)) throw new Error('Invalid flow name');
  return { type: 'bruno-flow', version: 1, name, flow };
}

module.exports = { normalizeFlowJson };
