import set from 'lodash/set';

export const buildFlowCompletionScope = (flow = {}, executions = [], context = {}) => {
  const ctx = { ...(flow.config?.initialVars || {}), ...context };
  for (const node of flow.nodes || []) {
    if (node.type === 'start') Object.assign(ctx, node.data?.initialVars);
    for (const key of Object.keys(node.data?.outputVars || {})) if (key.split('.').every((part) => !['__proto__', 'prototype', 'constructor'].includes(part))) set(ctx, key, undefined);
    if (['for-each', 'data-source', 'while'].includes(node.type)) {
      ctx[node.data?.indexVar || 'index'] = 0;
      if (node.type !== 'while') ctx[node.data?.itemVar || (node.type === 'data-source' ? 'row' : 'item')] = {};
    }
    for (const match of String(node.data?.code || '').matchAll(/\bctx\.([A-Za-z_$][\w$]*)\s*=(?!=)/g)) {
      if (!['__proto__', 'constructor', 'prototype'].includes(match[1])) ctx[match[1]] = undefined;
    }
  }
  const last = executions[executions.length - 1];
  const snapshot = last?.result?.output || last?.output || last?.input;
  return { ctx: { ...ctx, ...(snapshot?.context || {}) }, res: { status: undefined, body: {}, data: {}, headers: {}, ...(snapshot?.response || {}) } };
};
