const fs = require('node:fs/promises');
const path = require('node:path');
const { normalizeFlowJson } = require('../services/flow-json');

function registerFlowJsonIpc({ ipcMain, dialog, mainWindow }) {
  ipcMain.handle('renderer:import-flow-json', async (_event, { collectionPath } = {}) => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: 'Import Flow JSON', properties: ['openFile'], filters: [{ name: 'Flow JSON', extensions: ['json'] }]
    });
    if (result.canceled || !result.filePaths?.length) return { canceled: true };
    const filename = result.filePaths[0];
    if ((await fs.stat(filename)).size > 10 * 1024 * 1024) throw new Error('Flow JSON files must be smaller than 10 MB');
    const document = JSON.parse((await fs.readFile(filename, 'utf8')).replace(/^\uFEFF/, ''));
    return normalizeFlowJson(document, collectionPath);
  });
  ipcMain.handle('renderer:export-flow-json', async (_event, { name, flow, collectionPath } = {}) => {
    const document = await normalizeFlowJson({ type: 'bruno-flow', version: 1, name, flow }, collectionPath);
    const result = await dialog.showSaveDialog(mainWindow, {
      title: 'Export Flow JSON', defaultPath: `${path.basename(document.name)}.flow.json`,
      filters: [{ name: 'Flow JSON', extensions: ['json'] }]
    });
    if (result.canceled || !result.filePath) return { canceled: true };
    await fs.writeFile(result.filePath, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
    return { pathname: result.filePath };
  });
}
module.exports = { registerFlowJsonIpc };
