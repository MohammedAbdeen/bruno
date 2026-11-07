import React, { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import FlowExpressionInput, { getFlowCompletions } from './index';
import { buildFlowCompletionScope } from './scope';

const scope = { ctx: { token: 'secret', user: { id: 1 } }, res: { status: 200, body: { active: true } } };
test('completes context and nested response paths from observed values', () => {
  expect(getFlowCompletions('ctx.to', 6, scope).list).toEqual(['ctx.token']);
  expect(getFlowCompletions('res.body.', 9, scope).list).toEqual(['res.body.active']);
});
test('accepts a completion with the keyboard without changing the rest of the expression', () => {
  const Input = () => {
    const [text, setText] = useState('ctx.to');
    return <FlowExpressionInput aria-label="Expression" value={text} onChange={setText} scope={scope} />;
  };
  render(<Input />);
  const input = screen.getByLabelText('Expression');
  input.focus();
  input.setSelectionRange(6, 6);
  fireEvent.keyDown(input, { ctrlKey: true, code: 'Space', key: ' ' });
  expect(screen.getByRole('option', { name: 'ctx.token' })).toBeVisible();
  fireEvent.keyDown(input, { key: 'Enter' });
  expect(input).toHaveValue('ctx.token');
});
test('suggestions include declared variables and actual response shapes', () => {
  const result = buildFlowCompletionScope({ nodes: [{ type: 'start', data: { initialVars: { users: [] } } }, { type: 'request', data: { outputVars: { token: 'res.body.token' } } }] },
    [{ result: { output: { context: { userId: 1 }, response: { body: { user: { id: 1 } } } } } }]);
  expect(Object.keys(result.ctx)).toEqual(expect.arrayContaining(['users', 'token', 'userId']));
  expect(getFlowCompletions('res.body.user.', 14, result).list).toContain('res.body.user.id');
});
