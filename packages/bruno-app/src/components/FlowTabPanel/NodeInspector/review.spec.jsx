import React, { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import NodeInspector from './index';
import AdvancedSettings from './AdvancedSettings';
import toast from 'react-hot-toast';

jest.mock('platform', () => ({ os: { family: 'Linux' } }));
jest.mock('react-hot-toast', () => ({ __esModule: true, default: { success: jest.fn() } }));
jest.mock('utils/collections', () => ({ flattenItems: (items) => items, isItemARequest: (item) => item.type === 'http-request' }));

test.each([['script', 'JavaScript code', 'code'], ['condition', 'JavaScript expression', 'expression']])('completes context in the %s inspector', (type, label, field) => {
  const update = jest.fn();
  render(
    <NodeInspector
      selectedNode={{ id: 'node', type, data: { [field]: 'ctx.to' } }}
      completionScope={{ ctx: { total: 0 } }}
      onUpdateNode={update}
    />
  );
  const input = screen.getByLabelText(label);
  input.focus();
  input.setSelectionRange(6, 6);
  fireEvent.keyDown(input, { key: ' ', code: 'Space', ctrlKey: true });
  fireEvent.mouseDown(screen.getByRole('option', { name: 'ctx.total' }));
  expect(update).toHaveBeenCalledWith('node', expect.objectContaining({ [field]: 'ctx.total' }));
});

test('request picker stores portable references and displays legacy absolute references', () => {
  const update = jest.fn();
  render(
    <NodeInspector
      selectedNode={{ id: 'request', type: 'request', data: { itemRef: '/collection/auth/login.bru' } }}
      collection={{ pathname: '/collection', items: [{ uid: 'login', name: 'Login', type: 'http-request', pathname: '/collection/auth/login.bru' }] }}
      onUpdateNode={update}
    />
  );
  const select = screen.getByRole('combobox');
  expect(select).toHaveValue('auth/login.bru');
  fireEvent.change(select, { target: { value: 'auth/login.bru' } });
  expect(update).toHaveBeenCalledWith('request', expect.objectContaining({ itemRef: 'auth/login.bru' }));
  expect(screen.getByRole('button', { name: 'Configuration' }).closest('[data-inspector]')).not.toBeNull();
});
test('assertion textarea preserves spacing while persisting only nonblank checks', () => {
  const persisted = jest.fn();
  const Form = () => {
    const [data, setData] = useState({ assertions: [] });
    return (
      <AdvancedSettings
        type="assert"
        data={data}
        onChange={(key, value) => {
          persisted(value); setData({ ...data, [key]: value });
        }}
      />
    );
  };
  render(<Form />);
  const input = screen.getByLabelText('Assertions');
  fireEvent.change(input, { target: { value: 'true;\n\n  \nfalse;\n' } });
  expect(input).toHaveValue('true;\n\n  \nfalse;\n');
  expect(persisted).toHaveBeenLastCalledWith([{ expression: 'true;' }, { expression: 'false;' }]);
});

test('assertion snippets append checks and preserve existing checks', () => {
  const update = jest.fn();
  render(<AdvancedSettings type="assert" data={{ assertions: [{ expression: 'ctx.ready' }] }} onChange={update} />);
  fireEvent.click(screen.getByRole('button', { name: '+ Body contains ID' }));
  expect(update).toHaveBeenCalledWith('assertions', [{ expression: 'ctx.ready' }, { expression: 'res.body?.id !== undefined' }]);
});

test('request Overrides tab applies header, query, and JSON body fields', () => {
  const update = jest.fn();
  const Form = () => {
    const [data, setData] = useState({ itemRef: 'request.bru' });
    return (
      <NodeInspector
        selectedNode={{ id: 'req', type: 'request', data }}
        onUpdateNode={(_id, value) => {
          setData(value); update(value);
        }}
      />
    );
  };
  render(<Form />);
  fireEvent.click(screen.getByRole('button', { name: 'Overrides', exact: true }));
  fireEvent.click(screen.getByRole('button', { name: 'Add Header' }));
  fireEvent.change(screen.getByLabelText('Header name 1'), { target: { value: 'X-Flow' } });
  fireEvent.change(screen.getByLabelText('Header value 1'), { target: { value: '{{token}}' } });
  fireEvent.change(screen.getByLabelText('Body overrides'), { target: { value: '{"id":42}' } });
  fireEvent.click(screen.getByRole('button', { name: 'Apply body overrides' }));
  expect(update).toHaveBeenLastCalledWith(expect.objectContaining({ overrides: { headers: { 'X-Flow': '{{token}}' }, body: { id: 42 } } }));
  expect(toast.success).toHaveBeenCalledWith('Body overrides applied');
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
});

test('assertion text updates when the same node receives changed saved data', () => {
  const { rerender } = render(<AdvancedSettings type="assert" data={{ assertions: [{ expression: 'true' }] }} onChange={jest.fn()} />);
  rerender(<AdvancedSettings type="assert" data={{ assertions: [{ expression: 'ctx.updated' }] }} onChange={jest.fn()} />);
  expect(screen.getByLabelText('Assertions')).toHaveValue('ctx.updated');
});
