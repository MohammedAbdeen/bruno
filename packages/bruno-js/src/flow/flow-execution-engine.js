const get = require('lodash/get');
const set = require('lodash/set');
const cloneDeep = require('lodash/cloneDeep');
const omit = require('lodash/omit');
const { randomUUID } = require('crypto');
const vm = require('node:vm');
const Ajv = require('ajv');
const { parseDataset } = require('./flow-resources');
const { compileFlowExpression } = require('./expressions');

/**
 * FlowExecutionEngine
 * Coordinates DAG graph traversal, variable scoping, expression evaluation,
 * and step-by-step execution of Bruno Flows.
 */
class FlowExecutionEngine {
  constructor(options = {}) {
    this.options = options;
  }

  evaluate(expression, ctx, res) {
    return compileFlowExpression(expression).runInNewContext({ ctx, res }, { timeout: 1000 });
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
      return Boolean(this.evaluate(expression, ctx, res));
    } catch (err) {
      throw new Error(`Condition evaluation failed: ${err.message}`);
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

    const ids = new Set(nodes.map((node) => node.id));
    if (ids.size !== nodes.length) throw new Error('Duplicate flow node IDs');
    for (const edge of edges) {
      if (!ids.has(edge.source) || !ids.has(edge.target)) throw new Error('Edge references a missing node');
    }
    // Only explicit loop nodes may receive back edges. Other cycles are invalid.
    const loopTypes = ['for-each', 'while', 'data-source'];
    const nodeMap = new Map(nodes.map((node) => [node.id, node]));
    const visited = new Set();
    const active = new Set();
    const visit = (id) => {
      if (active.has(id)) throw new Error('Cycle must return to a loop node');
      if (visited.has(id)) return;
      active.add(id);
      for (const edge of edges.filter((edge) => edge.source === id)) {
        if (!loopTypes.includes(nodeMap.get(edge.target).type)) visit(edge.target);
      }
      active.delete(id);
      visited.add(id);
    };
    nodes.forEach((node) => visit(node.id));

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
    abortSignal,
    resolveFlow,
    loadDataset,
    initialContext = {},
    depth = 0,
    beforeStep
  }) {
    if (depth > 16) throw new Error('Sub-flow nesting limit exceeded (16)');
    const flow = get(flowItem, 'flow', {});
    const nodes = flow.nodes || [];
    const edges = flow.edges || [];
    const config = flow.config || {};
    const runId = `flow_run_${randomUUID()}`;
    const startTime = Date.now();

    const { startNode } = this.validateGraph(flow);

    // Initialize runtime context with initial variables
    let context = {
      ...(config.initialVars || {}),
      ...(get(startNode, 'data.initialVars', {})),
      ...cloneDeep(initialContext)
    };

    const loopStates = new Map();
    const steps = [];
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

      // Yield between steps so cancellation is delivered during tight loops.
      await new Promise((resolve) => setImmediate(resolve));
      if (abortSignal?.aborted) continue;
      if (executedNodes.length >= (config.maxSteps || 10000)) {
        runStatus = 'failed';
        runError = new Error('Flow step limit exceeded');
        break;
      }
      const nodeId = currentNode.id;
      const nodeType = currentNode.type;
      const nodeStartTime = Date.now();

      try {
        const resumed = await beforeStep?.({ node: currentNode, context, response: lastResponse, runId, flowName: flowItem.name || 'Untitled Flow', abortSignal });
        if (resumed?.context) context = cloneDeep(resumed.context);
      } catch (error) {
        runStatus = abortSignal?.aborted ? 'cancelled' : 'failed';
        runError = error;
        break;
      }
      if (abortSignal?.aborted) continue;
      executedNodes.push(nodeId);

      const stepId = `${runId}:${executedNodes.length}`;
      const input = {
        context: cloneDeep(context),
        response: cloneDeep(lastResponse),
        parameters: cloneDeep(omit(currentNode.data || {}, ['runStatus', 'conditionMet', 'response']))
      };
      const captureOutput = (result) => ({
        context: cloneDeep(context),
        response: cloneDeep(nodeType === 'request' ? result.response || null : lastResponse),
        details: cloneDeep(omit(result, ['input', 'output', 'response', 'subFlow'])),
        ...(result.subFlow ? { subFlow: { status: result.subFlow.status, error: result.subFlow.error } } : {})
      });
      onEvent('flow:node-started', {
        runId,
        nodeId,
        nodeType,
        timestamp: nodeStartTime,
        stepId,
        input: cloneDeep(input)
      });

      let stepResult = {
        stepId,
        input,
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

            if (res.requestSent || res.request) stepResult.input.request = cloneDeep(res.requestSent || res.request);
            if (res.updatedVariables && typeof res.updatedVariables === 'object' && !Array.isArray(res.updatedVariables)) {
              context = cloneDeep(res.updatedVariables);
            }
            if (res.error) throw new Error(res.error.message || String(res.error));
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
            context = this.extractOutputs(outputVars, lastResponse, context);
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
            if (!Number.isFinite(durationMs) || durationMs < 0) throw new Error('Invalid delay duration');
            await new Promise((resolve, reject) => {
              let timer;
              const cancel = () => {
                clearTimeout(timer);
                reject(new Error('Flow aborted during delay'));
              };
              timer = setTimeout(() => {
                abortSignal?.removeEventListener('abort', cancel);
                resolve();
              }, durationMs);
              abortSignal?.addEventListener('abort', cancel, { once: true });
              if (abortSignal?.aborted) cancel();
            });
            stepResult.delayMs = durationMs;
            break;
          }

          case 'script': {
            const code = get(currentNode, 'data.code', '');
            if (code && code.trim()) {
              vm.runInNewContext(code, { ctx: context, res: lastResponse || {} }, { timeout: 1000 });
            }
            break;
          }

          case 'for-each':
          case 'data-source':
          case 'while': {
            const data = currentNode.data || {};
            let state = loopStates.get(nodeId);
            if (!state) {
              state = { index: 0 };
              if (nodeType === 'for-each') {
                state.items = this.evaluate(data.expression || '[]', context, lastResponse || {});
              } else if (nodeType === 'data-source') {
                if (typeof loadDataset !== 'function') throw new Error('Dataset loader not provided');
                const content = await loadDataset(data.fileRef);
                state.items = parseDataset(content, data.format || 'json');
              }
              if (nodeType !== 'while' && !Array.isArray(state.items)) throw new Error('Loop input must be an array');
              loopStates.set(nodeId, state);
            }
            const hasNext = nodeType === 'while'
              ? Boolean(this.evaluate(data.expression || 'false', context, lastResponse || {}))
              : state.index < state.items.length;
            const limit = data.maxIterations ?? 1000;
            if (!Number.isInteger(limit) || limit < 1) throw new Error('maxIterations must be a positive integer');
            if (hasNext && state.index >= limit) throw new Error('Loop iteration limit exceeded');
            stepResult.nextHandle = hasNext ? 'body' : 'done';
            stepResult.iteration = state.index;
            if (hasNext) {
              if (nodeType !== 'while') context[data.itemVar || (nodeType === 'data-source' ? 'row' : 'item')] = cloneDeep(state.items[state.index]);
              context[data.indexVar || 'index'] = state.index++;
            } else {
              loopStates.delete(nodeId);
            }
            break;
          }

          case 'sub-flow': {
            if (typeof resolveFlow !== 'function') throw new Error('Sub-flow resolver not provided');
            const child = await resolveFlow(get(currentNode, 'data.flowRef'));
            const result = await this.run({
              flowItem: child, collection, environment, executeRequest, abortSignal,
              resolveFlow, loadDataset, initialContext: context, depth: depth + 1, beforeStep,
              onEvent: (type, data) => onEvent('flow:subflow-event', { nodeId, eventType: type, data })
            });
            stepResult.subFlow = result;
            if (result.status !== 'success') throw new Error(result.error || `Sub-flow ${result.status}`);
            context = result.context;
            break;
          }

          case 'assert': {
            const data = currentNode.data || {};
            const checks = (data.assertions || [{ expression: data.expression || '', message: data.message }])
              .filter((check) => typeof check?.expression !== 'string' || check.expression.trim());
            if (!Array.isArray(checks) || (!checks.length && data.schema === undefined)) throw new Error('At least one assertion is required');
            stepResult.assertions = [];
            if (data.schema !== undefined) {
              const validate = new Ajv({ allErrors: data.failFast === false, strict: false }).compile(data.schema);
              const passed = validate(lastResponse?.body);
              stepResult.assertions.push({ passed, message: passed ? 'Response schema passed' : new Ajv().errorsText(validate.errors) });
            }
            for (const check of checks) {
              if (data.failFast !== false && stepResult.assertions.some((check) => !check.passed)) break;
              let passed = false;
              let error;
              try {
                passed = Boolean(this.evaluate(check.expression, context, lastResponse || {}));
              } catch (err) {
                error = err.message;
              }
              stepResult.assertions.push({ expression: check.expression, passed, message: error || check.message || data.message || 'Assertion failed' });
              if (!passed && data.failFast !== false) break;
            }
            const failures = stepResult.assertions.filter((check) => !check.passed);
            if (failures.length) {
              stepResult.continueOnFailure = data.failFast === false;
              throw new Error(failures.map((check) => check.message).join('; '));
            }
            break;
          }

          case 'end': {
            isTerminated = true;
            stepResult.outputSummary = get(currentNode, 'data.outputSummary', {});
            break;
          }

          default:
            throw new Error(`Unknown node type: ${nodeType}`);
        }

        stepResult.duration = Date.now() - nodeStartTime;
        stepResult.output = captureOutput(stepResult);
        nodeResults[nodeId] = stepResult;
        steps.push(stepResult);

        onEvent('flow:node-completed', {
          runId,
          stepId,
          nodeId,
          nodeType,
          status: 'success',
          duration: stepResult.duration,
          result: stepResult,
          context: cloneDeep(context)
        });
      } catch (err) {
        if (['for-each', 'while', 'data-source'].includes(nodeType)) {
          loopStates.delete(nodeId);
          stepResult.nextHandle = 'done';
        }
        stepResult.status = 'failed';
        stepResult.error = err.message;
        stepResult.duration = Date.now() - nodeStartTime;
        stepResult.output = captureOutput(stepResult);
        nodeResults[nodeId] = stepResult;
        steps.push(stepResult);

        onEvent('flow:node-failed', {
          runId,
          stepId,
          nodeId,
          nodeType,
          error: err.message,
          duration: stepResult.duration,
          result: stepResult
        });

        runStatus = abortSignal?.aborted ? 'cancelled' : 'failed';
        runError = err;
        if (abortSignal?.aborted || (config.stopOnError !== false && !stepResult.continueOnFailure)) break;
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

      if (stepResult.nextHandle) {
        nextEdge = outgoingEdges.find((edge) => edge.sourceHandle === stepResult.nextHandle);
        if (!nextEdge && stepResult.nextHandle === 'body') {
          runStatus = 'failed';
          runError = new Error(`Loop ${nodeId} requires a Body connection`);
        }
      }

      if (!nextEdge) {
        break;
      }

      currentNode = nodeMap.get(nextEdge.target);
    }

    if (abortSignal?.aborted) {
      runStatus = 'cancelled';
      runError = runError || new Error('Flow aborted');
    }
    const totalDuration = Date.now() - startTime;

    const summary = {
      runId,
      status: runStatus,
      duration: totalDuration,
      executedNodes,
      nodeResults,
      steps,
      context,
      error: runError ? runError.message : null
    };

    if (runStatus === 'success' && loopStates.size) {
      summary.status = 'failed';
      summary.error = 'Loop body must connect back to its loop node';
    }
    onEvent('flow:finished', summary);

    return summary;
  }
}

module.exports = FlowExecutionEngine;
