import React, { useRef, useState, useId } from 'react';
import get from 'lodash/get';

export const getFlowCompletions = (text, cursor, scope) => {
  const before = text.slice(0, cursor);
  const match = before.match(/(?:ctx|res)(?:(?:\.[\w$]*|\[\d+\]))*$/);
  if (!match) return { from: cursor, list: before.trim() ? [] : ['ctx.', 'res.'] };
  const token = match[0];
  const dot = token.lastIndexOf('.');
  const base = dot < 0 ? token : token.slice(0, dot);
  const prefix = dot < 0 ? '' : token.slice(dot + 1);
  const value = get(scope, base);
  return { from: cursor - token.length, list: Object.keys(value || {}).filter((key) => /^[A-Za-z_$][\w$]*$/.test(key) && key.startsWith(prefix))
    .sort().slice(0, 40).map((key) => `${base}.${key}`) };
};

const EMPTY_COMPLETION = { from: 0, cursor: 0, list: [] };

const FlowExpressionInput = ({ value = '', onChange, scope = {}, multiline = false, ...props }) => {
  const field = useRef(null);
  const id = useId();
  const [completion, setCompletion] = useState(EMPTY_COMPLETION);
  const hasCompletion = completion.list.length > 0;
  const [active, setActive] = useState(0);
  const suggest = (text, cursor) => {
    const result = getFlowCompletions(text, cursor, scope);
    setCompletion({ ...result, cursor });
    setActive(0);
  };
  const choose = (candidate) => {
    if (!candidate || !hasCompletion) return;
    const next = value.slice(0, completion.from) + candidate + value.slice(completion.cursor);
    onChange(next);
    setCompletion(EMPTY_COMPLETION);
    requestAnimationFrame(() => {
      field.current?.focus(); field.current?.setSelectionRange(completion.from + candidate.length, completion.from + candidate.length);
    });
  };
  const Input = multiline ? 'textarea' : 'input';
  return (
    <div className="relative">
      <Input
        {...props}
        ref={field}
        value={value}
        aria-autocomplete="list"
        aria-expanded={hasCompletion}
        aria-controls={hasCompletion ? id : undefined}
        onChange={(event) => {
          onChange(event.target.value); suggest(event.target.value, event.target.selectionStart);
        }}
        onBlur={() => setCompletion(EMPTY_COMPLETION)}
        onKeyDown={(event) => {
          if ((event.ctrlKey || event.metaKey) && event.code === 'Space') {
            event.preventDefault(); event.stopPropagation(); suggest(value, field.current.selectionStart);
          } else if (hasCompletion && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
            event.preventDefault(); event.stopPropagation(); setActive((active + (event.key === 'ArrowDown' ? 1 : -1) + completion.list.length) % completion.list.length);
          } else if (hasCompletion && ['Tab', 'Enter'].includes(event.key)) {
            event.preventDefault(); event.stopPropagation(); choose(completion.list[active]);
          } else if (event.key === 'Escape') {
            setCompletion(EMPTY_COMPLETION); event.stopPropagation();
          }
        }}
      />
      {hasCompletion && (
        <ul id={id} role="listbox" aria-label="Flow completions" className="absolute z-50 left-0 right-0 max-h-40 overflow-auto border rounded shadow-lg bg-white dark:bg-zinc-900">
          {completion.list.map((candidate, index) => (
            <li
              key={candidate}
              role="option"
              aria-selected={index === active}
              className={`p-2 font-mono text-xs cursor-pointer ${index === active ? 'bg-indigo-100 dark:bg-indigo-900' : ''}`}
              onMouseDown={(event) => {
                event.preventDefault(); choose(candidate);
              }}
            >{candidate}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
export default FlowExpressionInput;
