import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import NodeExecutionDetails from './index';

const record = (stepId, before, after) => ({ stepId, status: 'success', duration: 2,
  result: { input: { context: { total: before } }, output: { context: { total: after } } } });

test('shows input and output for individual loop iterations', () => {
  render(<NodeExecutionDetails executions={[record('first', 0, 10), record('second', 10, 30)]} />);
  expect(JSON.parse(screen.getByTestId('node-input').textContent).context.total).toBe(10);
  expect(JSON.parse(screen.getByTestId('node-output').textContent).context.total).toBe(30);
  fireEvent.change(screen.getByLabelText('Node execution'), { target: { value: 'first' } });
  expect(JSON.parse(screen.getByTestId('node-input').textContent).context.total).toBe(0);
  expect(JSON.parse(screen.getByTestId('node-output').textContent).context.total).toBe(10);
});
test('shows live input while waiting for output', () => {
  render(<NodeExecutionDetails executions={[{ stepId: 'running', status: 'running', input: { context: { token: 'test' } } }]} />);
  expect(screen.getByTestId('node-input')).toHaveTextContent('test');
  expect(screen.getByText(/output appears when/)).toBeVisible();
});
test('shows errors and partial output for failures', () => {
  render(<NodeExecutionDetails executions={[{ ...record('failed', 0, 1), status: 'failed', error: 'Script failed' }]} />);
  expect(screen.getByRole('alert')).toHaveTextContent('Script failed');
  expect(JSON.parse(screen.getByTestId('node-output').textContent).context.total).toBe(1);
});
test('can display cyclic objects and bigint values', () => {
  const value = { number: BigInt(10) };
  value.self = value;
  render(<NodeExecutionDetails executions={[{ status: 'success', input: value }]} />);
  expect(screen.getByTestId('node-input')).toHaveTextContent('10n');
  expect(screen.getByTestId('node-input')).toHaveTextContent('[Circular]');
});

test('shows shared response body and data values without marking them as cycles', () => {
  const body = { token: 'test-token' };
  render(<NodeExecutionDetails executions={[{ status: 'success', output: { response: { body, data: body } } }]} />);
  const output = JSON.parse(screen.getByTestId('node-output').textContent);
  expect(output.response.body).toEqual(body);
  expect(output.response.data).toEqual(body);
});

test('Copy as cURL copies the request captured for the selected execution', async () => {
  const writeText = jest.fn().mockResolvedValue(undefined);
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } });
  try {
    render(
      <NodeExecutionDetails executions={[{ status: 'success', input: { request: {
        method: 'POST', url: 'https://example.com/captured?trace=42', headers: { 'Content-Type': 'application/json' }, data: '{"id":42}'
      } } }]}
      />
    );
    fireEvent.click(screen.getByRole('button', { name: 'Copy as cURL' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(expect.stringContaining('trace=42')));
    expect(writeText.mock.calls[0][0]).toContain('"id":42');
    expect(await screen.findByRole('status')).toHaveTextContent('cURL copied');
  } finally {
    if (original) Object.defineProperty(navigator, 'clipboard', original);
    else delete navigator.clipboard;
  }
});
