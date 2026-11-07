export const FLOW_TEMPLATES = [
  { id: 'blank', name: 'Blank Flow', requests: [] },
  { id: 'oauth', name: 'OAuth2 Login & Fetch', requests: [['login', 'Login request'], ['api', 'Authenticated API request']] },
  { id: 'poll', name: 'Async Job Polling Loop', requests: [['submit', 'Submit job request'], ['poll', 'Poll status request'], ['result', 'Get result request']] },
  { id: 'batch', name: 'Data-Driven Batch Run', requests: [['process', 'Process row request']] }
];

export const createFlowTemplate = (template = 'blank', references = {}) => {
  const nodes = [];
  const edges = [];
  const node = (id, type, data = {}, x = nodes.length * 320, y = 100) => nodes.push({ id, type, data, position: { x, y } });
  const edge = (source, target, sourceHandle) => edges.push({ id: `${source}-${target}`, source, target, ...(sourceHandle ? { sourceHandle } : {}) });
  const request = (id, extra = {}) => node(id, 'request', { itemRef: references[id] || '', ...extra });
  const assert = () => node('assert', 'assert', { expression: 'res.status === 200', failFast: true });
  node('start', 'start', { initialVars: {} });
  if (template === 'oauth') {
    request('login');
    node('extract', 'script', { code: 'ctx.token = res.body?.access_token || res.body?.token;\nif (!ctx.token) throw new Error(\'Login response did not contain a token\');' });
    request('api', { overrides: { headers: { Authorization: 'Bearer {{token}}' } } });
    assert();
    ['start', 'login', 'extract', 'api', 'assert'].forEach((id, i, ids) => { if (i) edge(ids[i - 1], id); });
    edge('assert', 'end');
  } else if (template === 'poll') {
    request('submit');
    node('loop', 'while', { expression: 'res.body?.status !== \'DONE\'', indexVar: 'pollIndex', maxIterations: 30 });
    node('delay', 'delay', { durationMs: 2000 }, 640, 350);
    node('poll', 'request', { itemRef: references.poll || '' }, 960, 350);
    request('result');
    assert();
    edge('start', 'submit'); edge('submit', 'loop'); edge('loop', 'delay', 'body'); edge('delay', 'poll'); edge('poll', 'loop');
    edge('loop', 'result', 'done'); edge('result', 'assert'); edge('assert', 'end');
  } else if (template === 'batch') {
    node('dataset', 'data-source', { fileRef: 'data.csv', format: 'csv', itemVar: 'row', indexVar: 'index', maxIterations: 1000 });
    request('process');
    assert();
    edge('start', 'dataset'); edge('dataset', 'process', 'body'); edge('process', 'assert'); edge('assert', 'dataset'); edge('dataset', 'end', 'done');
  } else {
    edge('start', 'end');
  }
  node('end', 'end');
  return { nodes, edges, config: { stopOnError: true }, viewport: { x: 0, y: 0, zoom: 1 } };
};
