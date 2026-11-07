import React from 'react';
import { render, screen, act, cleanup } from '@testing-library/react';
import ErrorBoundary from './index';

jest.mock('components/Bruno/index', () => () => <div />);
afterEach(() => {
  cleanup(); window.onerror = null;
});

test('layout notifications keep the application mounted', () => {
  render(<ErrorBoundary><p>Application</p></ErrorBoundary>);
  act(() => window.onerror('ResizeObserver loop completed with undelivered notifications.', '', 0, 0, undefined));
  expect(screen.getByText('Application')).toBeVisible();
});
test('actual JavaScript exceptions still show the error boundary', () => {
  render(<ErrorBoundary><p>Application</p></ErrorBoundary>);
  act(() => window.onerror('Unexpected error', '', 0, 0, new Error('Unexpected error')));
  expect(screen.getByText('Oops! Something went wrong')).toBeVisible();
});
