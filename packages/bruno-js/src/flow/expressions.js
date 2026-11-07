const vm = require('node:vm');

// Compile all supported forms before execution. Runtime errors must never trigger
// a retry: expressions may have side effects. Script completion values support
// statement sequences, while the last form supports an explicit `return`.
function compileFlowExpression(expression) {
  if (typeof expression !== 'string' || !expression.trim()) throw new SyntaxError('Expression is required');
  const clean = expression.trim().replace(/[;\s]+$/, '');
  try {
    return new vm.Script(`(${clean})`);
  } catch (error) {
    if (error.name !== 'SyntaxError') throw error;
  }
  try {
    return new vm.Script(expression);
  } catch (error) {
    if (error.name !== 'SyntaxError') throw error;
    return new vm.Script(`(() => { ${expression}\n})()`);
  }
}

module.exports = { compileFlowExpression };
