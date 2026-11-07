const fs = require('node:fs/promises');
const xmlbuilder = require('xmlbuilder');
const { cloneDeep } = require('lodash');
const { sanitizeResultsForReporter } = require('../utils/sanitize-results');

function flattenSteps(summary, prefix = '') {
  return (summary.steps || []).flatMap((step, index) => {
    const name = `${prefix}${step.nodeId} [${index + 1}]`;
    return [{ ...step, name }, ...(step.subFlow ? flattenSteps(step.subFlow, `${name}/`) : [])];
  });
}

function sanitizeSummary(summary, options) {
  for (const step of summary.steps || []) {
    const sanitizeExchange = (request, response) => {
      if (!request && !response) return;
      sanitizeResultsForReporter([{ request: request || {}, response: response || {} }], options);
      if (response && options.skipResponseBody) delete response.body;
    };
    sanitizeExchange(null, step.response);
    sanitizeExchange(step.input?.request, step.input?.response);
    sanitizeExchange(null, step.output?.response);
    if (step.subFlow) sanitizeSummary(step.subFlow, options);
  }
  // Rebuild latest-per-node entries from sanitized steps, avoiding duplicate raw data.
  summary.nodeResults = Object.fromEntries((summary.steps || []).map((step) => [step.nodeId, step]));
}

async function writeFlowReports({ summary, requestResults = [], formats, reporterOptions = {} }) {
  const output = { summary: cloneDeep(summary), requests: cloneDeep(requestResults) };
  sanitizeResultsForReporter(output.requests, reporterOptions);
  sanitizeSummary(output.summary, reporterOptions);
  for (const [format, filename] of Object.entries(formats)) {
    if (format === 'json') {
      await fs.writeFile(filename, JSON.stringify(output, null, 2));
    } else if (format === 'junit') {
      const steps = flattenSteps(summary);
      if (!steps.length || (summary.status !== 'success' && !steps.some((step) => step.status === 'failed'))) {
        steps.push({ name: 'Flow execution', status: summary.status, error: summary.error || summary.status, duration: 0 });
      }
      const failures = steps.filter((step) => step.status !== 'success').length;
      const suite = {
        '@name': 'Bruno Flow', '@tests': steps.length, '@failures': failures,
        '@errors': 0, '@time': (summary.duration / 1000).toFixed(3),
        'testcase': steps.map((step) => ({
          '@name': step.name, '@classname': 'Bruno.Flow', '@time': (step.duration / 1000).toFixed(3),
          ...(step.status !== 'success' ? { failure: { '@message': step.error || step.status } } : {})
        }))
      };
      await fs.writeFile(filename, xmlbuilder.create({ testsuites: { testsuite: suite } }).end({ pretty: true }));
    } else {
      throw new Error(`Unsupported flow reporter: ${format}`);
    }
  }
}

module.exports = { writeFlowReports, flattenSteps };
