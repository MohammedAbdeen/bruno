const vm = require('node:vm');
const { compileFlowExpression } = require('./expressions');
const { validateRequestOverrides } = require('./request-overrides');
const LOOP_TYPES = ['for-each', 'while', 'data-source'];
const TYPES = ['start', 'end', 'request', 'script', 'condition', 'delay', 'sub-flow', 'assert', ...LOOP_TYPES];

function validateFlow(flow = {}, { requests } = {}) {
  const issues = [];
  const add = (nodeId, code, message, severity = 'error') => issues.push({ nodeId, code, message, severity });
  const nodes = Array.isArray(flow.nodes) ? flow.nodes : [];
  const edges = Array.isArray(flow.edges) ? flow.edges : [];
  const ids = new Set();
  for (const node of nodes) {
    if (!node.id || ids.has(node.id)) add(node.id, 'duplicate-id', 'Node IDs must be present and unique');
    ids.add(node.id);
  }
  const adjacency = (id) => edges.filter((edge) => edge.source === id && ids.has(edge.target));
  const starts = nodes.filter((node) => node.type === 'start');
  if (starts.length !== 1) add(null, 'start', 'A complete flow needs exactly one Start node');
  if (!nodes.some((node) => node.type === 'end')) add(null, 'end', 'Add an End node');
  for (const edge of edges) if (!ids.has(edge.source) || !ids.has(edge.target)) add(ids.has(edge.source) ? edge.source : null, 'broken-edge', `Connection ${edge.id} references a missing node`);
  const reachable = new Set();
  const visit = (id) => {
    if (reachable.has(id)) return;
    reachable.add(id);
    adjacency(id).forEach((edge) => visit(edge.target));
  };
  starts.forEach((node) => visit(node.id));
  const declared = new Set(Object.keys(flow.config?.initialVars || {}));
  const references = new Set();
  for (const node of nodes) {
    const data = node.data || {};
    if (node.type === 'start') Object.keys(data.initialVars || {}).forEach((key) => declared.add(key));
    if (node.type === 'request') Object.keys(data.outputVars || {}).forEach((key) => declared.add(key.split('.')[0]));
    if (LOOP_TYPES.includes(node.type)) {
      declared.add(data.indexVar || 'index');
      if (node.type !== 'while') declared.add(data.itemVar || (node.type === 'data-source' ? 'row' : 'item'));
    }
    for (const match of String(data.code || '').matchAll(/\bctx\.([A-Za-z_$][\w$]*)\s*=(?!=)/g)) declared.add(match[1]);
  }
  const referenceMap = new Map();
  for (const request of requests || []) for (const alias of [request.itemRef, ...(request.aliases || [])]) referenceMap.set(alias, request);
  for (const node of nodes) {
    const data = node.data || {};
    const outgoing = adjacency(node.id);
    if (!TYPES.includes(node.type)) add(node.id, 'type', `Unsupported node type: ${node.type}`);
    if (!reachable.has(node.id)) add(node.id, 'unreachable', 'Node is unreachable from Start');
    if (node.type !== 'end' && !outgoing.length) add(node.id, 'dead-end', 'Connect this node to the next step');
    if (node.type === 'end' && outgoing.length) add(node.id, 'end-output', 'End nodes cannot have outgoing connections');
    if (node.type === 'start' && edges.some((edge) => edge.target === node.id)) add(node.id, 'start-input', 'Start cannot have incoming connections');
    const handles = node.type === 'condition' ? ['true', 'false'] : LOOP_TYPES.includes(node.type) ? ['body', 'done'] : null;
    if (handles) {
      if (outgoing.length !== 2 || handles.some((handle) => !outgoing.some((edge) => edge.sourceHandle === handle))) add(node.id, 'handles', `Connect both ${handles.join(' and ')} outputs`);
    } else if (outgoing.length > 1) add(node.id, 'multiple-outputs', 'Use a condition or loop to select between multiple outputs');
    if (LOOP_TYPES.includes(node.type)) {
      const body = outgoing.find((edge) => edge.sourceHandle === 'body');
      const pending = body ? [body.target] : [];
      const seen = new Set();
      let returns = false;
      let exits = false;
      while (pending.length) {
        const id = pending.pop();
        if (id === node.id) {
          returns = true; continue;
        }
        if (seen.has(id)) continue;
        seen.add(id);
        const next = adjacency(id);
        if (!next.length) exits = true;
        pending.push(...next.map((edge) => edge.target));
      }
      if (body && (!returns || exits)) add(node.id, 'loop-return', 'Every loop body path must return to the loop input');
      if (data.maxIterations !== undefined && (!Number.isInteger(data.maxIterations) || data.maxIterations < 1)) add(node.id, 'iterations', 'Maximum iterations must be a positive integer');
    }
    try {
      if (['condition', 'while', 'for-each'].includes(node.type)) compileFlowExpression(data.expression);
      if (node.type === 'script') new vm.Script(data.code || '');
      if (node.type === 'assert') {
        const checks = (data.assertions || [{ expression: data.expression || '' }]).filter((check) => check.expression?.trim());
        if (!checks.length && data.schema === undefined) throw new Error('Add an assertion or a response schema');
        checks.forEach((check) => compileFlowExpression(check.expression));
      }
    } catch (error) { add(node.id, 'syntax', error.message); }
    if (node.type === 'request') {
      if (!data.itemRef && !data.itemUid) add(node.id, 'request', 'Select a collection request');
      try { validateRequestOverrides(data.overrides); } catch (error) { add(node.id, 'overrides', error.message); }
      if (requests && data.itemRef && !referenceMap.has(data.itemRef)) add(node.id, 'missing-request', `Request not found: ${data.itemRef}`);
      for (const name of referenceMap.get(data.itemRef)?.inputs || []) {
        if (!declared.has(name) && !name.startsWith('$')) add(node.id, 'unmapped-input', `Request input "${name}" needs a flow mapping or an inherited variable`, 'warning');
      }
    }
    for (const field of ['expression', 'code']) for (const match of String(data[field] || '').matchAll(/\bctx\.([A-Za-z_$][\w$]*)/g)) {
      const key = `${node.id}:${match[1]}`;
      if (!declared.has(match[1]) && !references.has(key)) {
        references.add(key);
        add(node.id, 'unmapped-context', `ctx.${match[1]} has no declared source; check scripts and response mappings`, 'warning');
      }
    }
    if (node.type === 'sub-flow' && !data.flowRef) add(node.id, 'sub-flow', 'Choose a sub-flow file');
    if (node.type === 'data-source' && !data.fileRef) add(node.id, 'data-source', 'Choose a dataset file');
    if (node.type === 'delay' && (!Number.isFinite(Number(data.durationMs ?? 1000)) || Number(data.durationMs ?? 1000) < 0)) add(node.id, 'delay', 'Delay must be a nonnegative number');
  }
  // Detect cycles that do not return through an explicit loop node.
  const active = new Set();
  const visited = new Set();
  const cycle = (id) => {
    if (active.has(id)) {
      add(id, 'cycle', 'Cycle must return through a loop node'); return;
    }
    if (visited.has(id)) return;
    active.add(id);
    for (const edge of adjacency(id)) if (!LOOP_TYPES.includes(nodes.find((node) => node.id === edge.target)?.type)) cycle(edge.target);
    active.delete(id);
    visited.add(id);
  };
  nodes.forEach((node) => cycle(node.id));
  return { valid: !issues.some((issue) => issue.severity === 'error'), issues };
}
module.exports = { validateFlow };
