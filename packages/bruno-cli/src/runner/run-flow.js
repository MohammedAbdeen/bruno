const fs = require('node:fs/promises');
const path = require('node:path');
const chalk = require('chalk');
const { cloneDeep } = require('lodash');
const { parseRequest } = require('@usebruno/filestore');
const FlowExecutionEngine = require('@usebruno/js/src/flow/flow-execution-engine');
const { resolveCollectionFile } = require('@usebruno/js/src/flow/flow-resources');
const { findItemInCollection } = require('../utils/collection');
const { writeFlowReports } = require('../reporters/flow');
const { applyRequestOverrides } = require('@usebruno/js/src/flow/request-overrides');

async function runFlowCli({ flowPath, collection, initialContext = {}, executeItem, formats = {}, reporterOptions = {}, bail = false, log = console.log }) {
  for (const [format, output] of Object.entries(formats)) {
    if (!['json', 'junit'].includes(format)) throw new Error('Flow reports support JSON and JUnit formats');
    await fs.access(path.dirname(path.resolve(output)));
  }
  const loadFlow = async (reference) => {
    const pathname = await resolveCollectionFile(collection.pathname, reference);
    const format = ['.yml', '.yaml'].includes(path.extname(pathname).toLowerCase()) ? 'yml' : 'bru';
    const item = parseRequest(await fs.readFile(pathname, 'utf8'), { format });
    if (item.type !== 'flow') throw new Error(`Expected a flow file: ${reference}`);
    return item;
  };
  const controller = new AbortController();
  const cancel = () => controller.abort();
  process.on('SIGINT', cancel);
  process.on('SIGTERM', cancel);
  const requestResults = [];
  let summary;
  try {
    const flowItem = await loadFlow(flowPath);
    if (bail) flowItem.flow.config = { ...flowItem.flow.config, stopOnError: true };
    log(chalk.bold(`Running flow: ${flowItem.name}`));
    summary = await new FlowExecutionEngine().run({
      flowItem,
      collection,
      initialContext: cloneDeep(initialContext),
      abortSignal: controller.signal,
      resolveFlow: loadFlow,
      loadDataset: async (reference) => fs.readFile(await resolveCollectionFile(collection.pathname, reference), 'utf8'),
      executeRequest: async ({ itemRef, overrides, context }) => {
        if (typeof itemRef !== 'string' || !itemRef.trim()) throw new Error('Request node requires an itemRef');
        // Older desktop flows may contain absolute request paths. Normalize them
        // before applying the same collection boundary check as relative paths.
        const relativeRef = path.isAbsolute(itemRef) ? path.relative(collection.pathname, itemRef) : itemRef;
        const reference = relativeRef.endsWith(collection.format === 'yml' ? '.yml' : '.bru')
          ? relativeRef : `${relativeRef}${collection.format === 'yml' ? '.yml' : '.bru'}`;
        const pathname = await resolveCollectionFile(collection.pathname, reference);
        const item = cloneDeep(findItemInCollection(collection, pathname));
        if (!item || !['http-request', 'graphql-request'].includes(item.type)) {
          throw new Error(`Flow request not found or unsupported: ${itemRef}`);
        }
        const result = await executeItem(applyRequestOverrides(item, overrides), context, controller.signal);
        requestResults.push({ ...cloneDeep(result), path: itemRef, name: item.name });
        const failedChecks = ['testResults', 'assertionResults', 'preRequestTestResults', 'postResponseTestResults']
          .flatMap((key) => result[key] || []).filter((check) => check.status === 'fail');
        if (result.shouldStopRunnerExecution) controller.abort();
        return {
          ...result.response,
          request: result.request,
          updatedVariables: result.updatedVariables,
          error: result.error || (failedChecks.length ? `${failedChecks.length} request test(s) or assertion(s) failed` : undefined)
        };
      },
      onEvent: (type, data) => {
        if (type === 'flow:node-completed') log(chalk.green(`✓ ${data.nodeId} (${data.nodeType}) ${data.duration}ms`));
        if (type === 'flow:node-failed') log(chalk.red(`✗ ${data.nodeId}: ${data.error}`));
        if (type === 'flow:subflow-event' && ['flow:node-completed', 'flow:node-failed'].includes(data.eventType)) {
          log(chalk.dim(`  ${data.nodeId} > ${data.data.nodeId}: ${data.eventType === 'flow:node-failed' ? data.data.error : 'completed'}`));
        }
      }
    });
  } catch (error) {
    summary = { status: controller.signal.aborted ? 'cancelled' : 'failed', error: error.message,
      duration: 0, steps: [], nodeResults: {}, context: {} };
  } finally {
    process.removeListener('SIGINT', cancel);
    process.removeListener('SIGTERM', cancel);
  }
  log((summary.status === 'success' ? chalk.green : chalk.red)(`Flow ${summary.status}: ${summary.steps.length} steps, ${summary.duration}ms${summary.error ? ` — ${summary.error}` : ''}`));
  await writeFlowReports({ summary, requestResults, formats, reporterOptions });
  return summary;
}

module.exports = { runFlowCli };
