import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import FlowDebugger from './index';

test('steps with edited context and rejects malformed context', async () => {
  const resume = jest.fn(async () => {});
  render(<FlowDebugger paused={{ nodeId: 'script', flowName: 'Example', context: { count: 1 } }} onResume={resume} />);
  fireEvent.change(screen.getByLabelText('Paused context'), { target: { value: '{"count": 5}' } });
  fireEvent.click(screen.getByRole('button', { name: 'Step Next' }));
  await waitFor(() => expect(resume).toHaveBeenCalledWith('step', { count: 5 }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled());
  fireEvent.change(screen.getByLabelText('Paused context'), { target: { value: '[]' } });
  fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('JSON object');
  expect(resume).toHaveBeenCalledTimes(1);
});
