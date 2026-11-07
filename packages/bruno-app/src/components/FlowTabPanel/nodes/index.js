import React from 'react';
import AdvancedNode from './AdvancedNode';
import StartNode from './StartNode';
import RequestNode from './RequestNode';
import ConditionNode from './ConditionNode';
import DelayNode from './DelayNode';
import ScriptNode from './ScriptNode';
import EndNode from './EndNode';

const baseNodeTypes = {
  'start': StartNode,
  'request': RequestNode,
  'condition': ConditionNode,
  'delay': DelayNode,
  'script': ScriptNode,
  'end': EndNode,
  'for-each': AdvancedNode,
  'while': AdvancedNode,
  'sub-flow': AdvancedNode,
  'data-source': AdvancedNode,
  'assert': AdvancedNode
};

export const nodeTypes = Object.fromEntries(Object.entries(baseNodeTypes).map(([type, Component]) => {
  const DecoratedNode = (props) => {
    const issues = props.data?.validationIssues || [];
    return (
      <div className={`relative ${props.data?.debugPaused ? 'ring-2 ring-amber-500 rounded-lg' : ''}`}>
        <Component {...props} />
        {issues.length > 0 && (
          <span
            data-testid="flow-node-issues"
            aria-label="Node has validation issues"
            title={issues.map((issue) => issue.message).join('\n')}
            className={`absolute -top-3 right-1 rounded-full px-2 py-0.5 text-xs text-white ${issues.some((issue) => issue.severity === 'error') ? 'bg-rose-600' : 'bg-amber-600'}`}
          >⚠ {issues.length}
          </span>
        )}
        {props.data?.breakpoint === true && <span aria-label="Breakpoint" title="Breakpoint" className="absolute -top-2 -left-2 text-rose-600">●</span>}
      </div>
    );
  };
  return [type, DecoratedNode];
}));
