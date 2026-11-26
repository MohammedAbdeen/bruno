const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { promisify } = require('node:util');
const execFile = promisify(require('node:child_process').execFile);
const { jsonToBruV2 } = require('@usebruno/lang');
const { normalizeFlowJson } = require('../../../packages/bruno-electron/src/services/flow-json');
const { createServer } = require('./mock-server.cjs');

async function main() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'bruno-json-examples-'));
  const server = createServer();
  const cli = path.resolve(__dirname, '../../../packages/bruno-cli/bin/bru.js');
  let checked = 0;
  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(0, '127.0.0.1', resolve);
    });
    for (const resource of ['bruno.json', 'requests', 'data', 'flows']) {
      await fs.cp(path.join(__dirname, resource), path.join(directory, resource), { recursive: true });
    }
    const files = (await fs.readdir(__dirname)).filter((file) => /^\d.*\.json$/.test(file)).sort();
    for (const file of files) {
      const input = JSON.parse(await fs.readFile(path.join(__dirname, file), 'utf8'));
      const normalized = await normalizeFlowJson(input, directory);
      const nativeFile = `flows/${file.replace(/\.json$/, '.flow.bru')}`;
      await fs.writeFile(path.join(directory, nativeFile), jsonToBruV2({
        meta: { name: normalized.name, type: 'flow' }, flow: normalized.flow
      }));
      const run = async () => {
        let exitCode = 0;
        try {
          await execFile(process.execPath, [cli, 'run', '--flow', nativeFile,
            '--env-var', `baseUrl=http://127.0.0.1:${server.address().port}`,
            '--env-var', 'buildId=9421', '--noproxy', '--disable-cookies',
            '--reporter-json', 'report.json', '--reporter-junit', 'report.xml'], {
            cwd: directory, timeout: 20000, maxBuffer: 2 * 1024 * 1024,
            env: { ...process.env, NO_COLOR: '1' }
          });
        } catch (error) {
          if (error.code !== 1) throw error;
          exitCode = error.code;
        }
        const { summary } = JSON.parse(await fs.readFile(path.join(directory, 'report.json'), 'utf8'));
        const expectedFailure = /^(12|13|14|15)-/.test(file);
        assert.equal(exitCode, expectedFailure ? 1 : 0, file);
        assert.equal(summary.status, expectedFailure ? 'failed' : 'success', `${file}: ${summary.error}`);
        assert.ok(summary.steps.every((step) => step.input && step.output), `${file}: missing step I/O`);
        const xml = await fs.readFile(path.join(directory, 'report.xml'), 'utf8');
        assert.ok(xml.includes('<testsuite'), `${file}: missing JUnit suite`);
        if (file.startsWith('12-') || file.startsWith('13-')) {
          assert.equal(summary.context.cleanedUp, true);
          assert.ok(summary.executedNodes.includes('end'));
        }
        if (file.startsWith('13-')) assert.equal(summary.nodeResults.checks.assertions.length, 2);
        if (file.startsWith('14-')) {
          assert.equal(summary.nodeResults.checks.assertions.length, 1);
          assert.equal(summary.context.shouldNotRun, undefined);
          assert.ok(!summary.executedNodes.includes('end'));
        }
        if (file.startsWith('15-')) {
          assert.match(summary.error, /iteration limit/);
          assert.equal(summary.context.ticks, 2);
        }
        checked++;
        console.log(`PASS ${file}${expectedFailure ? ' (expected failure verified)' : ''}`);
        return summary;
      };
      await run();
      if (file.startsWith('05-')) {
        normalized.flow.nodes.find((node) => node.id === 'start').data.initialVars.usePrimary = false;
        await fs.writeFile(path.join(directory, nativeFile), jsonToBruV2({
          meta: { name: normalized.name, type: 'flow' }, flow: normalized.flow
        }));
        assert.equal((await run()).context.route, 'fallback');
      }
    }
    console.log(`Verified ${files.length} JSON imports and ${checked} CLI runs, including both condition branches.`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(directory, { recursive: true, force: true });
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
