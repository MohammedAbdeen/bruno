import React, { useState, useEffect } from 'react';
import FlowExpressionInput from '../FlowExpressionInput';
import SchemaInput from './SchemaInput';

const inputClass = 'w-full p-2 border rounded bg-white dark:bg-zinc-900 dark:border-zinc-600 font-mono text-xs';
const snippets = [
  ['Status 200', 'res.status === 200'],
  ['Response Time < 500ms', 'res.duration < 500'],
  ['Content-Type JSON', 'res.headers[\'content-type\']?.includes(\'application/json\')'],
  ['Body contains ID', 'res.body?.id !== undefined']
];

const AdvancedSettings = ({ type, data, onChange, completionScope = {} }) => {
  const savedAssertions = data.assertions ? data.assertions.map((check) => check.expression).join('\n') : data.expression || '';
  const [assertionText, setAssertionText] = useState(savedAssertions);
  useEffect(() => {
    setAssertionText((current) => current.split('\n').map((line) => line.trim()).filter(Boolean).join('\n') === savedAssertions ? current : savedAssertions);
  }, [savedAssertions]);
  const loop = ['for-each', 'while', 'data-source'].includes(type);
  const text = (field, label, placeholder, fallback = '') => (
    <label className="block space-y-1">
      <span>{label}</span>
      <input
        className={inputClass}
        value={data[field] ?? fallback}
        placeholder={placeholder}
        onChange={(event) => onChange(field, event.target.value)}
      />
    </label>
  );
  return (
    <div className="space-y-3">
      {['for-each', 'while'].includes(type) && (
        <label className="block space-y-1">
          <span>{type === 'while' ? 'Continue while (JavaScript)' : 'Array expression (JavaScript)'}</span>
          <FlowExpressionInput aria-label="Loop expression" scope={completionScope} value={data.expression || ''} className={inputClass} onChange={(value) => onChange('expression', value)} />
        </label>
      )}
      {type === 'sub-flow' && (
        <>
          {text('flowRef', 'Flow file relative to collection', 'flows/login.flow.bru')}
          <p>Inherits a copy of ctx and merges the child’s final context on success. Maximum nesting: 16.</p>
        </>
      )}
      {type === 'data-source' && (
        <>
          {text('fileRef', 'Dataset file relative to collection', 'data/users.json')}
          <label className="block">Format
            <select className={inputClass} value={data.format || 'json'} onChange={(event) => onChange('format', event.target.value)}>
              <option value="json">JSON array</option><option value="csv">CSV with headers</option>
            </select>
          </label>
        </>
      )}
      {loop && (
        <>
          {type !== 'while' && text('itemVar', 'Current item variable (ctx)', 'item', type === 'data-source' ? 'row' : 'item')}
          {text('indexVar', 'Index variable (ctx, starts at 0)', 'index', 'index')}
          <label className="block">Maximum iterations
            <input
              className={inputClass}
              type="number"
              min="1"
              step="1"
              value={data.maxIterations ?? 1000}
              onChange={(event) => onChange('maxIterations', Number(event.target.value))}
            />
          </label>
          <p>Connect Body to the steps to repeat, then connect the last step back to this node. Connect Done to the next step after the loop. Expressions can use ctx and res.</p>
        </>
      )}
      {type === 'assert' && (
        <>
          <label className="block space-y-1">Assertions (one JavaScript expression per line)
            <FlowExpressionInput
              multiline
              aria-label="Assertions"
              scope={completionScope}
              className={inputClass}
              rows={5}
              value={assertionText}
              onChange={(value) => {
                setAssertionText(value);
                onChange('assertions', value.split('\n').map((expression) => expression.trim()).filter(Boolean).map((expression) => ({ expression })));
              }}
            />
          </label>
          <div className="flex gap-1 flex-wrap" aria-label="Assertion snippets">
            {snippets.map(([label, expression]) => (
              <button
                key={label}
                className="px-2 py-1 border rounded"
                onClick={() => {
                  const next = `${assertionText.trimEnd()}${assertionText.trim() ? '\n' : ''}${expression}`;
                  setAssertionText(next);
                  onChange('assertions', next.split('\n').map((line) => line.trim()).filter(Boolean).map((expression) => ({ expression })));
                }}
              >+ {label}
              </button>
            ))}
          </div>
          <SchemaInput schema={data.schema} onChange={(schema) => onChange('schema', schema)} />
          {text('message', 'Failure message', 'Assertion failed')}
          <label className="flex gap-2 items-center">
            <input type="checkbox" checked={data.failFast !== false} onChange={(event) => onChange('failFast', event.target.checked)} />
            Stop on first failure
          </label>
          <p>Uncheck to evaluate all assertions and continue the flow. The final run still reports failure.</p>
        </>
      )}
    </div>
  );
};
export default AdvancedSettings;
