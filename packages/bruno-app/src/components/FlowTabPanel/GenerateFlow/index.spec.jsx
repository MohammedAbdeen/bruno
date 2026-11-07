import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { getAiStatus, aiGenerateFlow, stopAiGeneration } from 'utils/ai';
import GenerateFlow from './index';

jest.mock('utils/ai', () => ({ getAiStatus: jest.fn(), aiGenerateFlow: jest.fn(), stopAiGeneration: jest.fn() }));
jest.mock('./context', () => ({ buildFlowRequests: () => [{ pathname: '/collection/login.bru', name: 'Login' }] }));
const candidate = { flow: { nodes: [{ id: 'start', type: 'start', data: {} },
  { id: 'login', type: 'request', data: { itemRef: 'login.bru', outputVars: { token: 'res.body.token' } } },
  { id: 'end', type: 'end', data: {} }], edges: [{ id: 'e1', source: 'start', target: 'login' }, { id: 'e2', source: 'login', target: 'end' }] }, notices: [] };
const props = () => ({ collection: { pathname: '/collection', items: [] }, onApply: jest.fn(), onClose: jest.fn() });
const describeFlow = async () => {
  await waitFor(() => expect(screen.getByRole('button', { name: 'Generate preview' })).toBeDisabled());
  fireEvent.change(screen.getByLabelText('Flow description'), { target: { value: 'Log in and verify inventory' } });
  await waitFor(() => expect(screen.getByRole('button', { name: 'Generate preview' })).toBeEnabled());
};
beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: () => 'flow-generation-test' });
  getAiStatus.mockResolvedValue({ enabled: true, availableModels: [{ id: 'test-model', name: 'Test model' }] });
  aiGenerateFlow.mockResolvedValue(candidate);
});

test('generation shows a preview and mappings before applying a replacement', async () => {
  const handlers = props();
  render(<GenerateFlow {...handlers} />);
  await describeFlow();
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  await screen.findByText('3 nodes, 2 connections');
  expect(screen.getByText('ctx.token ← res.body.token')).toBeInTheDocument();
  expect(handlers.onApply).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: 'Replace flow with preview' }));
  expect(handlers.onApply).toHaveBeenCalledWith(candidate.flow);
  expect(handlers.onClose).toHaveBeenCalled();
});
test('provider errors are displayed without replacing the flow', async () => {
  aiGenerateFlow.mockResolvedValue({ error: 'Provider unavailable' });
  const handlers = props();
  render(<GenerateFlow {...handlers} />);
  await describeFlow();
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Provider unavailable');
  expect(handlers.onApply).not.toHaveBeenCalled();
});
test('cancellation discards late results', async () => {
  let finish;
  aiGenerateFlow.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  render(<GenerateFlow {...props()} />);
  await describeFlow();
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel generation' }));
  expect(stopAiGeneration).toHaveBeenCalledWith('flow-generation-test');
  await act(async () => finish(candidate));
  expect(screen.queryByRole('button', { name: 'Replace flow with preview' })).not.toBeInTheDocument();
});
test('closing while generating stops the provider request', async () => {
  aiGenerateFlow.mockImplementation(() => new Promise(() => {}));
  const handlers = props();
  render(<GenerateFlow {...handlers} />);
  await describeFlow();
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  expect(stopAiGeneration).toHaveBeenCalledWith('flow-generation-test');
  expect(handlers.onClose).toHaveBeenCalled();
});
test('editing the description discards the previous preview', async () => {
  render(<GenerateFlow {...props()} />);
  await describeFlow();
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  await screen.findByText('3 nodes, 2 connections');
  fireEvent.change(screen.getByLabelText('Flow description'), { target: { value: 'Different workflow' } });
  expect(screen.queryByRole('button', { name: 'Replace flow with preview' })).not.toBeInTheDocument();
});
test('requires an enabled provider', async () => {
  getAiStatus.mockResolvedValue({ enabled: false, availableModels: [] });
  render(<GenerateFlow {...props()} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Preferences > AI');
  fireEvent.change(screen.getByLabelText('Flow description'), { target: { value: 'Log in' } });
  expect(screen.getByRole('button', { name: 'Generate preview' })).toBeDisabled();
});
test('passes the selected model and prevents replacement during execution', async () => {
  render(<GenerateFlow {...props()} isRunning />);
  await describeFlow();
  fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'test-model' } });
  fireEvent.click(screen.getByRole('button', { name: 'Generate preview' }));
  await screen.findByText('3 nodes, 2 connections');
  expect(aiGenerateFlow).toHaveBeenCalledWith(expect.objectContaining({ model: 'test-model' }));
  expect(screen.getByRole('button', { name: 'Replace flow with preview' })).toBeDisabled();
});
