const fs = require('node:fs/promises');
const path = require('node:path');
const { parseRequest } = require('@usebruno/filestore');
const { parseDataset, resolveCollectionFile } = require('@usebruno/js/src/flow/flow-resources');

async function loadFlowFile(collectionPath, reference) {
  const pathname = await resolveCollectionFile(collectionPath, reference);
  const format = ['.yml', '.yaml'].includes(path.extname(pathname).toLowerCase()) ? 'yml' : 'bru';
  const parsed = parseRequest(await fs.readFile(pathname, 'utf8'), { format });
  if (parsed.type !== 'flow' || !parsed.flow) throw new Error('Sub-flow reference must point to a flow');
  return parsed;
}

module.exports = { parseDataset, resolveCollectionFile, loadFlowFile };
