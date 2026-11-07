import { test as base, expect, waitForReadyPage, closeElectronApp, Page, ElectronApplication } from '../../playwright';
import { buildCommonLocators } from '../utils/page';
import * as fs from 'node:fs/promises';
import * as path from 'node:path';

const test = base.extend<{ flowFormat: 'bru' | 'yml'; flowSession: { page: Page; app: ElectronApplication; restart: () => Promise<Page> } }>({
  flowFormat: ['bru', { option: true }],
  flowSession: async ({ launchElectronApp, collectionFixturePath, createTmpDir, flowFormat }, use, testInfo) => {
    if (flowFormat === 'yml') {
      await fs.unlink(path.join(collectionFixturePath!, 'bruno.json'));
      await fs.writeFile(path.join(collectionFixturePath!, 'opencollection.yml'), 'opencollection: "1.0.0"\ninfo:\n  name: Flow validation\n');
    }
    const userDataPath = await createTmpDir('flow-user-data');
    let app = await launchElectronApp({
      userDataPath,
      initUserDataPath: path.join(__dirname, 'init-user-data'),
      templateVars: { collectionPath: collectionFixturePath! }
    });
    let page = await waitForReadyPage(app);
    await app.evaluate(({ BrowserWindow }) => {
      for (const window of BrowserWindow.getAllWindows()) window.show();
    });
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));
    page.on('console', (message) => { if (message.type() === 'error') pageErrors.push(message.text()); });
    try {
      await use({
        page,
        get app() { return app; },
        restart: async () => {
          await closeElectronApp(app);
          app = await launchElectronApp({ userDataPath });
          page = await waitForReadyPage(app);
          await app.evaluate(({ BrowserWindow }) => {
            for (const window of BrowserWindow.getAllWindows()) window.show();
          });
          page.on('pageerror', (error) => pageErrors.push(error.message));
          page.on('console', (message) => { if (message.type() === 'error') pageErrors.push(message.text()); });
          return page;
        }
      });
    } finally {
      try {
        if (testInfo.status !== testInfo.expectedStatus && !page.isClosed()) {
          await testInfo.attach('flow-page-errors', { body: JSON.stringify(pageErrors), contentType: 'application/json' });
          await testInfo.attach('flow-dom', { body: await page.locator('body').innerText({ timeout: 3000 }), contentType: 'text/plain' });
          await testInfo.attach('flow-page', { body: await page.screenshot({ timeout: 5000 }), contentType: 'image/png' });
        }
      } finally {
        await closeElectronApp(app);
      }
    }
  }
});

test('creates a flow through the collection menu', async ({ flowSession, collectionFixturePath }) => {
  let page = flowSession.page;
  let locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click({ button: 'right' });
  await page.getByText('New Flow', { exact: true }).click();
  await page.getByTestId('new-flow-name-input').fill('Created flow');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Run Flow', exact: true })).toBeVisible();
  await expect.poll(async () => (await fs.readdir(collectionFixturePath!)).some((name) => name.startsWith('Created flow') && name.endsWith('.bru'))).toBe(true);
});

test('saves, reloads, and executes an edited flow', async ({ flowSession, collectionFixturePath }) => {
  let page = flowSession.page;
  let locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  await page.locator('textarea').fill('ctx.result = "persisted-value";');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8')).toContain('persisted-value');
  page = await flowSession.restart();
  locators = buildCommonLocators(page);
  await expect(locators.sidebar.collection('Flow validation')).toBeVisible();
  const savedFlow = page.locator('.collection-item-name .item-name[title="Smoke"]');
  if (!await savedFlow.isVisible()) await locators.sidebar.collection('Flow validation').click();
  await savedFlow.click();
  const run = page.getByRole('button', { name: 'Run Flow', exact: true });
  await run.click();
  await page.getByText('Flow Execution Timeline', { exact: true }).click();
  await page.getByRole('button', { name: 'Context Variables', exact: true }).click();
  await expect(page.locator('pre').filter({ hasText: 'persisted-value' })).toBeVisible();
});

test('Stop cancels an executing delay and reports cancellation', async ({ flowSession }) => {
  const page = flowSession.page;
  let locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.getByText('Cancel', { exact: true }).click();
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await page.getByRole('button', { name: 'Stop Flow', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Run Flow', exact: true })).toBeVisible();
  await page.getByText('Flow Execution Timeline', { exact: true }).click();
  await expect(page.getByText(/^cancelled \(/)).toBeVisible();
});

test('exports JSON and imports it as a separate runnable flow', async ({ flowSession, collectionFixturePath }) => {
  const page = flowSession.page;
  const filename = path.join(collectionFixturePath!, 'exported-flow.json');
  await flowSession.app.evaluate(({ dialog }, filename) => {
    dialog.showSaveDialog = async () => ({ canceled: false, filePath: filename });
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [filename] });
  }, filename);
  const locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.getByRole('button', { name: 'Export JSON', exact: true }).click();
  await expect.poll(async () => JSON.parse(await fs.readFile(filename, 'utf8')).type).toBe('bruno-flow');
  const exported = JSON.parse(await fs.readFile(filename, 'utf8'));
  expect(exported.flow.nodes).toHaveLength(3);
  expect(exported.flow.edges).toHaveLength(2);
  expect(exported.flow.nodes[1].data.code).toContain('original');
  await fs.writeFile(filename, JSON.stringify({ ...exported, name: 'Imported Smoke' }));
  await page.getByRole('button', { name: 'Import JSON', exact: true }).click();
  await expect(page.getByText('Flow imported from JSON', { exact: true })).toBeVisible();
  expect(await fs.readFile(path.join(collectionFixturePath!, 'Imported Smoke.bru'), 'utf8')).toContain('original');
  expect(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8')).toContain('original');
  await expect(page.locator('.tab-name[title="Imported Smoke"]')).toBeVisible();
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await page.getByText('Flow Execution Timeline', { exact: true }).click();
  await page.getByRole('button', { name: 'Context Variables', exact: true }).click();
  await expect(page.locator('pre').filter({ hasText: 'original' })).toBeVisible();
});

test('runs datasets, sub-flows, loops, HTTP requests, and assertions together', async ({ flowSession }) => {
  const page = flowSession.page;
  const locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.getByText('Advanced', { exact: true }).click();
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await expect(page.getByText(/^success \(/)).toBeVisible();
  await page.getByText('Flow Execution Timeline', { exact: true }).click();
  await page.getByRole('button', { name: 'Context Variables', exact: true }).click();
  const context = JSON.parse((await page.locator('pre').filter({ hasText: 'statusCode' }).textContent())!);
  expect(context).toMatchObject({ total: 8, pollCount: 2, statusCode: 200 });
});

test('rejects invalid JSON and cancels import without changing existing flows', async ({ flowSession, collectionFixturePath }) => {
  const page = flowSession.page;
  const filename = path.join(collectionFixturePath!, 'invalid.json');
  await fs.writeFile(filename, '{}');
  const before = (await fs.readdir(collectionFixturePath!)).sort();
  await flowSession.app.evaluate(({ dialog }, filename) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [filename] });
  }, filename);
  const locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.getByRole('button', { name: 'Import JSON', exact: true }).click();
  await expect(page.getByText(/Flow JSON requires nodes and edges arrays/)).toBeVisible();
  await flowSession.app.evaluate(({ dialog }) => {
    dialog.showOpenDialog = async () => ({ canceled: true, filePaths: [] });
  });
  await page.getByRole('button', { name: 'Import JSON', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Import JSON', exact: true })).toBeEnabled();
  expect((await fs.readdir(collectionFixturePath!)).sort()).toEqual(before);
  await expect(page.getByRole('button', { name: 'Run Flow', exact: true })).toBeVisible();
});

test('imports a flow into the selected folder from its context menu', async ({ flowSession, collectionFixturePath }) => {
  const page = flowSession.page;
  const filename = path.join(collectionFixturePath!, 'folder-import.json');
  await fs.writeFile(filename, JSON.stringify({ type: 'bruno-flow', version: 1, name: 'Folder Flow',
    flow: { nodes: [{ id: 'start', type: 'start', data: {} }], edges: [] } }));
  await flowSession.app.evaluate(({ dialog }, filename) => {
    dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [filename] });
  }, filename);
  const locators = buildCommonLocators(page);
  await locators.sidebar.collection('Flow validation').click();
  await page.locator('.collection-item-name .item-name[title="Target"]').click({ button: 'right' });
  await page.getByText('Import Flow (JSON)', { exact: true }).click();
  await expect(page.getByText('Flow imported from JSON', { exact: true })).toBeVisible();
  expect(await fs.readFile(path.join(collectionFixturePath!, 'Target', 'Folder Flow.bru'), 'utf8')).toContain('name: Folder Flow');
  await expect(page.locator('.tab-name[title="Folder Flow"]')).toBeVisible();
});

for (const format of ['bru', 'yml'] as const) {
  test.describe(`Provided JSON example in ${format} collections`, () => {
    test.use({ flowFormat: format });
    test('imports Sum Example, restarts, and verifies total 60', async ({ flowSession, collectionFixturePath }) => {
      let page = flowSession.page;
      const filename = path.resolve(__dirname, '../../examples/flows/sum-flow.json');
      await flowSession.app.evaluate(({ dialog }, filename) => {
        dialog.showOpenDialog = async () => ({ canceled: false, filePaths: [filename] });
      }, filename);
      await buildCommonLocators(page).sidebar.collection('Flow validation').click({ button: 'right' });
      await page.getByText('Import Flow (JSON)', { exact: true }).click();
      await expect(page.getByText('Flow imported from JSON', { exact: true })).toBeVisible();
      const saved = path.join(collectionFixturePath!, `Sum Example.${format}`);
      expect(await fs.readFile(saved, 'utf8')).toContain('Sum Example');
      page = await flowSession.restart();
      const collection = buildCommonLocators(page).sidebar.collection('Flow validation');
      await expect(collection).toBeVisible();
      const item = page.locator('.collection-item-name .item-name[title="Sum Example"]');
      if (!await item.isVisible()) await collection.click();
      await item.click();
      await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
      await expect(page.getByText(/^success \(/)).toBeVisible();
      await page.getByText('Flow Execution Timeline', { exact: true }).click();
      await page.getByRole('button', { name: 'Context Variables', exact: true }).click();
      const context = JSON.parse((await page.locator('pre').filter({ hasText: 'total' }).textContent())!);
      expect(context.total).toBe(60);
    });
  });
}

test('deletes a connected node, removes its edges, and persists deletion', async ({ flowSession, collectionFixturePath }) => {
  let page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  await page.getByRole('button', { name: 'Delete node', exact: true }).click();
  await expect(page.locator('.react-flow__node-script')).toHaveCount(0);
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const { bruToJsonV2 } = require('@usebruno/lang');
  await expect.poll(async () => bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8')).flow.nodes.length).toBe(2);
  const saved = bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8'));
  expect(saved.flow.edges).toEqual([]);
  page = await flowSession.restart();
  const collection = buildCommonLocators(page).sidebar.collection('Flow validation');
  await expect(collection).toBeVisible();
  const flow = page.locator('.collection-item-name .item-name[title="Smoke"]');
  if (!await flow.isVisible()) await collection.click();
  await flow.click();
  await expect(page.getByRole('button', { name: 'Run Flow', exact: true })).toBeVisible();
  await expect(page.locator('.react-flow__node-script')).toHaveCount(0);
});

test('Delete and Backspace delete nodes on the canvas but edit text in inputs', async ({ flowSession }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  const code = page.locator('textarea');
  await code.fill('abc');
  await code.press('Backspace');
  await expect(code).toHaveValue('ab');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(1);
  const canvas = page.getByRole('group', { name: 'Flow canvas', exact: true });
  await page.getByRole('button', { name: 'Script', exact: true }).click();
  await expect(page.locator('.react-flow__node-script')).toHaveCount(2);
  await canvas.focus();
  await canvas.press('Delete');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(1);
  await expect(page.locator('.react-flow__edge')).toHaveCount(2);
  await page.locator('.react-flow__node-script').click();
  await canvas.focus();
  await canvas.press('Backspace');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(0);
  await expect(page.locator('.react-flow__edge')).toHaveCount(0);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
});

test('shows independent input/output for every execution of a loop node', async ({ flowSession }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Loop inspection', { exact: true }).click();
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await expect(page.getByText(/^success \(/)).toBeVisible();
  await page.locator('.react-flow__node-script').click();
  await expect(page.getByRole('button', { name: 'Input / Output', exact: true })).toHaveAttribute('aria-pressed', 'true');
  const execution = page.getByLabel('Node execution', { exact: true });
  await expect(execution.locator('option')).toHaveCount(3);
  await execution.selectOption({ index: 0 });
  expect(JSON.parse((await page.getByTestId('node-input').textContent())!).context.total).toBe(0);
  expect(JSON.parse((await page.getByTestId('node-output').textContent())!).context.total).toBe(10);
  await execution.selectOption({ index: 2 });
  expect(JSON.parse((await page.getByTestId('node-input').textContent())!).context.total).toBe(30);
  expect(JSON.parse((await page.getByTestId('node-output').textContent())!).context.total).toBe(60);
});

test('inspector buttons never delete the selected node and request references are portable', async ({ flowSession, collectionFixturePath }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  const configuration = page.getByRole('button', { name: 'Configuration', exact: true });
  await configuration.focus();
  await configuration.press('Delete');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(1);
  const io = page.getByRole('button', { name: 'Input / Output', exact: true });
  await io.click();
  await io.press('Backspace');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(1);
  await page.getByRole('button', { name: 'Request', exact: true }).click();
  await page.locator('[data-inspector] select').selectOption('Ping.bru');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const { bruToJsonV2 } = require('@usebruno/lang');
  await expect.poll(async () => {
    const item = bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8'));
    return item.flow.nodes.find((node) => node.type === 'request')?.data.itemRef;
  }).toBe('Ping.bru');
});

test('validates flows, marks errors, and styles loop return edges', async ({ flowSession }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Loop inspection', { exact: true }).click();
  await expect(page.locator('.flow-loop-back-edge')).toHaveCount(1);
  await page.getByRole('button', { name: 'Validate Flow', exact: true }).click();
  await expect(page.getByText('Flow validation passed', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Script', exact: true }).click();
  await page.getByRole('button', { name: 'Validate Flow', exact: true }).click();
  await expect(page.getByText('Flow has validation errors', { exact: true })).toBeVisible();
  await expect(page.getByText('Node is unreachable from Start', { exact: true })).toBeVisible();
  await expect(page.getByTestId('flow-node-issues').first()).toBeVisible();
});

test('completes ctx fields in the script editor', async ({ flowSession }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Loop inspection', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  const code = page.getByLabel('JavaScript code', { exact: true });
  await code.fill('ctx.to');
  await code.press('Control+Space');
  await expect(page.getByRole('option', { name: 'ctx.total', exact: true })).toBeVisible();
  await code.press('Enter');
  await expect(code).toHaveValue('ctx.total');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
});

test('debugger pauses at breakpoints, steps, edits ctx, and continues', async ({ flowSession }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  await page.getByRole('checkbox', { name: /Breakpoint/ }).check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Debug Flow', exact: true }).click();
  const debuggerPanel = page.getByRole('region', { name: 'Flow debugger', exact: true });
  await expect(debuggerPanel.getByText(/Paused before start/)).toBeVisible();
  await debuggerPanel.getByLabel('Paused context').fill('{"debugValue": 7}');
  await debuggerPanel.getByRole('button', { name: 'Step Next', exact: true }).click();
  await expect(debuggerPanel.getByText(/Paused before script/)).toBeVisible();
  await expect(debuggerPanel.getByLabel('Paused context')).toHaveValue(/"debugValue": 7/);
  await debuggerPanel.getByRole('button', { name: 'Continue', exact: true }).click();
  await expect(page.getByText(/^success \(/)).toBeVisible();
  await expect(debuggerPanel).toHaveCount(0);
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await expect(debuggerPanel.getByText(/Paused before script/)).toBeVisible();
  await page.getByRole('button', { name: 'Stop Flow', exact: true }).click();
  await expect(page.getByText(/^cancelled \(/)).toBeVisible();
  await expect(debuggerPanel).toHaveCount(0);
});

test('duplicates configured nodes, beautifies positions, and inserts assertion snippets', async ({ flowSession, collectionFixturePath }) => {
  const page = flowSession.page;
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Smoke', { exact: true }).click();
  await page.locator('.react-flow__node-script').click();
  const canvas = page.getByRole('group', { name: 'Flow canvas', exact: true });
  await canvas.focus();
  await canvas.press('Control+d');
  await expect(page.locator('.react-flow__node-script')).toHaveCount(2);
  await page.locator('.react-flow__node-script.selected').click({ button: 'right' });
  await page.getByRole('menuitem', { name: 'Duplicate Node', exact: true }).click();
  await expect(page.locator('.react-flow__node-script')).toHaveCount(3);
  await page.getByRole('button', { name: 'Assert', exact: true }).click();
  await page.getByRole('button', { name: '+ Body contains ID', exact: true }).click();
  await expect(page.getByLabel('Assertions', { exact: true })).toHaveValue(/res.body\?\.id !== undefined/);
  await page.getByRole('button', { name: 'Beautify Graph', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  const { bruToJsonV2 } = require('@usebruno/lang');
  await expect.poll(async () => bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8')).flow.nodes.length).toBe(6);
  const saved = bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, 'Smoke.flow.bru'), 'utf8')).flow;
  expect(new Set(saved.nodes.map((node) => node.id)).size).toBe(6);
  expect(saved.nodes.filter((node) => node.type === 'script').every((node) => node.data.code.includes('original'))).toBe(true);
  expect(saved.nodes.find((node) => node.id === 'end').position.x).not.toBe(560);
});

for (const template of ['oauth', 'poll', 'batch']) {
  test(`creates the ${template} starter template with selected collection requests`, async ({ flowSession, collectionFixturePath }) => {
    const page = flowSession.page;
    await buildCommonLocators(page).sidebar.collection('Flow validation').click({ button: 'right' });
    await page.getByText('New Flow', { exact: true }).click();
    await page.getByTestId('new-flow-name-input').fill(`Template ${template}`);
    await page.getByLabel('Starter template', { exact: true }).selectOption(template);
    const fields = template === 'oauth' ? ['Login request', 'Authenticated API request'] : template === 'poll' ? ['Submit job request', 'Poll status request', 'Get result request'] : ['Process row request'];
    for (const label of fields) await page.getByLabel(label, { exact: true }).selectOption('Ping.bru');
    await page.getByRole('button', { name: 'Create', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Run Flow', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Validate Flow', exact: true }).click();
    await expect(page.getByText('Flow validation passed', { exact: true })).toBeVisible();
    const { bruToJsonV2 } = require('@usebruno/lang');
    await expect.poll(async () => (await fs.readdir(collectionFixturePath!)).find((name) => name.startsWith(`Template ${template}`))).toBeTruthy();
    const filename = (await fs.readdir(collectionFixturePath!)).find((name) => name.startsWith(`Template ${template}`))!;
    const saved = bruToJsonV2(await fs.readFile(path.join(collectionFixturePath!, filename), 'utf8')).flow;
    expect(saved.nodes.filter((node) => node.type === 'request').every((node) => node.data.itemRef === 'Ping.bru')).toBe(true);
  });
}

test('applies step overrides and copies the captured request as cURL', async ({ flowSession, installFakeClipboard, collectionFixturePath }) => {
  const page = flowSession.page;
  const clipboard = await installFakeClipboard(page);
  await buildCommonLocators(page).sidebar.collection('Flow validation').click();
  await page.getByText('Overrides', { exact: true }).click();
  await page.locator('.react-flow__node-request').click();
  await page.getByRole('button', { name: 'Overrides', exact: true }).click();
  await page.getByRole('button', { name: 'Add Header', exact: true }).click();
  await page.getByLabel('Header name 1', { exact: true }).fill('X-Flow');
  await page.getByLabel('Header value 1', { exact: true }).fill('captured');
  await page.getByRole('button', { name: 'Add Query parameter', exact: true }).click();
  await page.getByLabel('Query parameter name 1', { exact: true }).fill('trace');
  await page.getByLabel('Query parameter value 1', { exact: true }).fill('{{buildId}}');
  await page.getByLabel('Body overrides', { exact: true }).fill('{"id":"{{buildId}}"}');
  await page.getByRole('button', { name: 'Apply body overrides', exact: true }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await page.getByRole('button', { name: 'Run Flow', exact: true }).click();
  await expect(page.getByText(/^success \(/)).toBeVisible();
  await page.getByRole('button', { name: 'Input / Output', exact: true }).click();
  await page.getByRole('button', { name: 'Copy as cURL', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'cURL copied' })).toBeVisible();
  const curl = await clipboard.copiedText();
  expect(curl).toContain('trace=9421');
  expect(curl).toContain('X-Flow: captured');
  expect(curl).toContain('9421');
  expect(await fs.readFile(path.join(collectionFixturePath!, 'Echo.bru'), 'utf8')).not.toContain('X-Flow');
});
