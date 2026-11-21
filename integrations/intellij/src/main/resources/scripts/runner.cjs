// Line-delimited IPC keeps credentials lazy; this file runs outside the IDE JVM.
const fs = require('fs');
let pending = Buffer.alloc(0);
function line() {
  let index;
  while ((index = pending.indexOf(10)) < 0) {
    const buffer = Buffer.alloc(8192); const count = fs.readSync(0, buffer, 0, buffer.length, null);
    if (!count) throw Error('Script channel closed');
    pending = Buffer.concat([pending, buffer.subarray(0, count)]);
    if (pending.length > 24 * 1024 * 1024) throw Error('Script input exceeds limit');
  }
  const result = pending.subarray(0, index).toString('utf8'); pending = pending.subarray(index + 1); return result;
}
const send = value => fs.writeSync(1, JSON.stringify(value) + '\n');
async function execute(input) {
  const deleted = new Set();
  function variables(initial) {
    const target = { ...initial };
    return new Proxy(target, {
      get(t, key) {
        if (typeof key === 'string' && input.secretKeys.includes(key) && !Object.hasOwn(t, key) && !deleted.has(key)) {
          send({ kind: 'secret', name: key });
          const reply = JSON.parse(line()); if (reply.error) throw Error('Saved secret unavailable: ' + key); t[key] = reply.value;
        }
        return Reflect.get(t, key);
      },
      has(t, key) { return Reflect.has(t, key) || input.secretKeys.includes(key) && !deleted.has(key); },
      getOwnPropertyDescriptor(t, key) {
        return Reflect.getOwnPropertyDescriptor(t, key) || (input.secretKeys.includes(key) && !deleted.has(key)
          ? { configurable: true, enumerable: false, writable: true, value: undefined } : undefined);
      },
      set(t, key, value) { deleted.delete(key); t[key] = value; return true; },
      deleteProperty(t, key) { deleted.add(key); return delete t[key]; }
    });
  }
  const runtimeModule = require(input.modulePath);
  const runtime = new runtimeModule.ScriptRuntime({ runtime: 'quickjs' });
  const env = variables(input.variables), vars = variables(input.runtimeVariables || {});
  const request = input.request; request.globalEnvironmentVariables = variables(input.variables);
  const logs = [];
  const log = (kind, values) => { if (logs.join('').length < 4000) logs.push(kind + ': ' + JSON.stringify(values)); };
  const result = input.phase === 'pre'
    ? await runtime.runRequestScript(input.script, request, env, vars, input.projectPath, log, {}, { allowScriptFilesystemAccess: false })
    : await runtime.runResponseScript(input.script, request, input.response, env, vars, input.projectPath, log, {}, { allowScriptFilesystemAccess: false });
  send({ kind: 'result', result, deleted: [...deleted], logs });
}
send({ kind: 'ready' });
(async () => {
  while (true) {
    let input;
    try { input = JSON.parse(line()); } catch { return; }
    try { await execute(input); }
    catch (error) { send({ kind: 'error', message: String(error.message || error) }); }
  }
})().catch(() => { process.exitCode = 1; });
