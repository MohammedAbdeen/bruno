const path = require('node:path');
const { validateFlow } = require('@usebruno/js/src/flow/validate-flow');

function registerFlowValidationIpc({ ipcMain }) {
  ipcMain.handle('renderer:validate-flow', async (_event, { flow, collection }) => {
    const requests = [];
    const walk = (items) => {
      for (const item of items || []) {
        if (item.type === 'folder') {
          walk(item.items); continue;
        }
        if (!['http-request', 'graphql-request'].includes(item.type) || !item.pathname) continue;
        const itemRef = path.relative(collection.pathname, item.pathname).split(path.sep).join('/');
        const inputs = [...JSON.stringify(item.request || {}).matchAll(/\{\{\s*([^{}]+?)\s*\}\}/g)].map((match) => match[1]);
        requests.push({ itemRef, aliases: [item.pathname, itemRef.replace(/\.(bru|yml)$/, '')], inputs });
      }
    };
    walk(collection?.items);
    return validateFlow(flow, { requests });
  });
}
module.exports = { registerFlowValidationIpc };
