const get = require('lodash/get');
const set = require('lodash/set');
const cloneDeep = require('lodash/cloneDeep');

/**
 * FlowExecutionEngine
 * Coordinates DAG graph traversal, variable scoping, expression evaluation,
 * and step-by-step execution of Bruno Flows.
 */
class FlowExecutionEngine {
  constructor(options = {}) {
    this.options = options;
  }

  /**
   * Safely evaluate a JS expression with access to `ctx`, `res`, and standard globals.
   */
  evaluateCondition(expression, scope = {}) {
    if (!expression || typeof expression !== 'string' || !expression.trim()) {
      return true;
    }

    try {
      const { ctx = {}, res = {} } = scope;
      // Scoped evaluation function avoiding outer scope pollution
      const fn = new Function('ctx', 'res', `"use strict"; return Boolean(${expression});`);
      return Boolean(fn(ctx, res));
    } catch (err) {
      console.error(`FlowEngine: Condition evaluation error for "${expression}":`, err.message);
      return false;
    }
  }

  /**
   * Extract response properties (JSONPath / dot-notation) into flow context.
   */
  extractOutputs(outputVars = {}, response = {}, context = {}) {
    if (!outputVars || typeof outputVars !== 'object') {
      return context;
    }

    const updatedContext = { ...context };

    for (const [varName, pathExpr] of Object.entries(outputVars)) {
      if (!pathExpr || typeof pathExpr !== 'string') continue;

      let cleanPath = pathExpr.trim();
      let sourceObj = response;

      if (cleanPath.startsWith('res.')) {
        cleanPath = cleanPath.slice(4);
      } else if (cleanPath.startsWith('response.')) {
        cleanPath = cleanPath.slice(9);
      }

      const extractedValue = get(sourceObj, cleanPath);
      if (extractedValue !== undefined) {
        set(updatedContext, varName, extractedValue);
      }
    }

    return updatedContext;
  }

  /**
   * Validate flow graph structure and locate entry point(s).
   */
  validateGraph(flow = {}) {
    const nodes = flow.nodes || [];
    const edges = flow.edges || [];

    if (!nodes.length) {
      throw new Error('Flow has no nodes to execute');
    }

    // Find start node explicitly, or fall back to node with in-degree 0
    let startNode = nodes.find((n) => n.type === 'start');

    if (!startNode) {
      const targetIds = new Set(edges.map((e) => e.target));
      startNode = nodes.find((n) => !targetIds.has(n.id));
    }

    if (!startNode) {
      startNode = nodes[0];
    }

    return {
      isValid: true,
      startNode,
      nodesCount: nodes.length,
      edgesCount: edges.length
    };
  }

  /**
   * Main entry point to run a flow.
   */
  async run({
    flowItem,
    collection = {},
    environment = {},
    executeRequest,
    onEvent = () => {},
    abortSignal
  }) {
    const flow = get(flowItem, 'flow', {});
    const nodes = flow.nodes || [];
    const edges = flow.edges || [];
    const config = flow.config || {};
    const runId = `flow_run_${Date.now()}`;
    const startTime = Date.now();

    const { startNode } = this.validateGraph(flow);

    // Initialize runtime context with initial variables
    let context = {
      ...(config.initialVars || {}),
      ...(get(startNode, 'data.initialVars', {}))
    };

    const executedNodes = [];
    const nodeResults = {};
    let lastResponse = null;
    let currentNode = startNode;
    let isTerminated = false;
    let runStatus = 'success';
    let runError = null;

    onEvent('flow:started', {
      runId,
      flowName: flowItem.name || 'Untitled Flow',
      totalNodes: nodes.length,
      timestamp: startTime
    });

    const nodeMap = new Map(nodes.map((n) => [n.id, n]));

    while (currentNode && !isTerminated) {
      if (abortSignal && abortSignal.aborted) {
        runStatus = 'cancelled';
        onEvent('flow:cancelled', { runId, timestamp: Date.now() });
        break;
      }

      const nodeId = currentNode.id;
      const nodeType = currentNode.type;
      const nodeStartTime = Date.now();

      executedNodes.push(nodeId);

      onEvent('flow:node-started', {
        runId,
        nodeId,
        nodeType,
        timestamp: nodeStartTime
      });

      let stepResult = {
        nodeId,
        nodeType,
        status: 'success',
        duration: 0
      };

      try {
        switch (nodeType) {
          case 'start': {
            // Nothing to execute; parameters are already initialized into context
            break;
          }

          case 'request': {
            if (typeof executeRequest !== 'function') {
              throw new Error('Request executor not provided to FlowExecutionEngine');
            }

            const itemRef = get(currentNode, 'data.itemRef');
            const itemUid = get(currentNode, 'data.itemUid');
            const overrides = get(currentNode, 'data.overrides', {});
            const outputVars = get(currentNode, 'data.outputVars', {});

            const res = await executeRequest({
              itemRef,
              itemUid,
              overrides,
              context,
              environment
            });

            lastResponse = {
              status: res.status,
              statusText: res.statusText,
              duration: res.duration || res.responseTime,
              headers: res.headers,
              data: res.data !== undefined ? res.data : res.body,
              body: res.data !== undefined ? res.data : res.body
            };
            stepResult.response = lastResponse;

            // Extract outputs into context
            context = this.extractOutputs(outputVars, res, context);
            stepResult.extractedVars = outputVars;
            break;
          }

          case 'condition': {
            const expression = get(currentNode, 'data.expression', 'true');
            const conditionMet = this.evaluateCondition(expression, {
              ctx: context,
              res: lastResponse || {}
            });

            stepResult.conditionMet = conditionMet;
            stepResult.expression = expression;
            break;
          }

          case 'delay': {
            const durationMs = Number(get(currentNode, 'data.durationMs', 1000));
            await new Promise((resolve, reject) => {
              const timer = setTimeout(resolve, durationMs);
              if (abortSignal) {
                abortSignal.addEventListener('abort', () => {
                  clearTimeout(timer);
                  reject(new Error('Flow aborted during delay'));
                }, { once: true });
              }
            });
            stepResult.delayMs = durationMs;
            break;
          }

          case 'script': {
            const code = get(currentNode, 'data.code', '');
            if (code && code.trim()) {
              const fn = new Function('ctx', 'res', `"use strict";\n${code}`);
              fn(context, lastResponse || {});
            }
            break;
          }

          case 'end': {
            isTerminated = true;
            stepResult.outputSummary = get(currentNode, 'data.outputSummary', {});
            break;
          }

          default:
            console.warn(`FlowEngine: Unknown node type: ${nodeType}`);
        }

        stepResult.duration = Date.now() - nodeStartTime;
        nodeResults[nodeId] = stepResult;

        onEvent('flow:node-completed', {
          runId,
          nodeId,
          nodeType,
          status: 'success',
          duration: stepResult.duration,
          result: stepResult,
          context
        });
      } catch (err) {
        stepResult.status = 'failed';
        stepResult.error = err.message;
        stepResult.duration = Date.now() - nodeStartTime;
        nodeResults[nodeId] = stepResult;

        onEvent('flow:node-failed', {
          runId,
          nodeId,
          nodeType,
          error: err.message,
          duration: stepResult.duration
        });

        if (config.stopOnError !== false) {
          runStatus = 'failed';
          runError = err;
          break;
        }
      }

      if (isTerminated) {
        break;
      }

      // Determine next node from outgoing edges
      const outgoingEdges = edges.filter((e) => e.source === nodeId);

      if (!outgoingEdges.length) {
        break;
      }

      let nextEdge = outgoingEdges[0];

      if (nodeType === 'condition') {
        const requiredHandle = stepResult.conditionMet ? 'true' : 'false';
        const matchingEdge = outgoingEdges.find((e) => e.sourceHandle === requiredHandle);
        nextEdge = matchingEdge || null;
      }

      if (!nextEdge) {
        break;
      }

      currentNode = nodeMap.get(nextEdge.target);
    }

    const totalDuration = Date.now() - startTime;

    const summary = {
      runId,
      status: runStatus,
      duration: totalDuration,
      executedNodes,
      nodeResults,
      context,
      error: runError ? runError.message : null
    };

    onEvent('flow:finished', summary);

    return summary;
  }
}

module.exports = FlowExecutionEngine;
