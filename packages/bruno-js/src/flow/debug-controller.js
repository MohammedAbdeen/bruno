const cloneDeep = require('lodash/cloneDeep');

class FlowDebugController {
  constructor({ pauseOnStart = false, onPause = () => {} } = {}) {
    this.stepping = pauseOnStart;
    this.onPause = onPause;
    this.pending = null;
    this.sequence = 0;
  }

  async beforeStep({ node, context, response, runId, flowName, abortSignal }) {
    if (!this.stepping && node.data?.breakpoint !== true) return;
    if (abortSignal?.aborted) throw new Error('Flow aborted');
    return new Promise((resolve, reject) => {
      const cancel = () => {
        this.pending = null;
        reject(new Error('Flow aborted while paused'));
      };
      const cleanup = () => abortSignal?.removeEventListener('abort', cancel);
      this.pending = {
        resume: (variables) => {
          cleanup(); this.pending = null; resolve(variables === undefined ? undefined : { context: cloneDeep(variables) });
        }
      };
      abortSignal?.addEventListener('abort', cancel, { once: true });
      this.onPause({ pauseId: `${runId}:${++this.sequence}`, runId, flowName, nodeId: node.id, nodeType: node.type,
        context: cloneDeep(context), response: cloneDeep(response) });
    });
  }

  resume(action, context) {
    if (!this.pending) throw new Error('Flow is not paused');
    if (!['step', 'continue'].includes(action)) throw new Error('Unknown debugger action');
    if (context !== undefined && (!context || typeof context !== 'object' || Array.isArray(context))) throw new Error('Context must be a JSON object');
    this.stepping = action === 'step';
    this.pending.resume(context);
  }
}
module.exports = { FlowDebugController };
