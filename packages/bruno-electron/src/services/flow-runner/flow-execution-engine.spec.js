const FlowExecutionEngine = require('./flow-execution-engine');

describe('FlowExecutionEngine', () => {
  let engine;

  beforeEach(() => {
    engine = new FlowExecutionEngine();
  });

  it('should validate graph and identify start node', () => {
    const flow = {
      nodes: [
        { id: 'start-1', type: 'start' },
        { id: 'req-1', type: 'request' }
      ],
      edges: [{ id: 'e1', source: 'start-1', target: 'req-1' }]
    };

    const validation = engine.validateGraph(flow);
    expect(validation.isValid).toBe(true);
    expect(validation.startNode.id).toBe('start-1');
  });

  it('should extract response outputs into context', () => {
    const outputVars = {
      authToken: 'res.body.token',
      userId: 'res.body.user.id',
      statusCode: 'res.status'
    };

    const response = {
      status: 200,
      body: {
        token: 'secret-token-123',
        user: { id: 42, name: 'Alice' }
      }
    };

    const context = { initial: true };
    const updated = engine.extractOutputs(outputVars, response, context);

    expect(updated.initial).toBe(true);
    expect(updated.authToken).toBe('secret-token-123');
    expect(updated.userId).toBe(42);
    expect(updated.statusCode).toBe(200);
  });

  it('should execute a linear flow and pass extracted variables to downstream nodes', async () => {
    const events = [];
    const onEvent = (type, data) => events.push({ type, data });

    const flowItem = {
      name: 'Auth and Fetch Flow',
      flow: {
        nodes: [
          {
            id: 'start-1',
            type: 'start',
            data: { initialVars: { envName: 'staging' } }
          },
          {
            id: 'req-login',
            type: 'request',
            data: {
              itemRef: 'auth/login.bru',
              outputVars: {
                token: 'res.body.token'
              }
            }
          },
          {
            id: 'req-profile',
            type: 'request',
            data: {
              itemRef: 'users/profile.bru'
            }
          },
          {
            id: 'end-1',
            type: 'end'
          }
        ],
        edges: [
          { id: 'e1', source: 'start-1', target: 'req-login' },
          { id: 'e2', source: 'req-login', target: 'req-profile' },
          { id: 'e3', source: 'req-profile', target: 'end-1' }
        ]
      }
    };

    const requestsExecuted = [];
    const executeRequest = async ({ itemRef, context }) => {
      requestsExecuted.push({ itemRef, context: { ...context } });
      if (itemRef === 'auth/login.bru') {
        return {
          status: 200,
          statusText: 'OK',
          body: { token: 'jwt-abc-123' }
        };
      }
      return {
        status: 200,
        statusText: 'OK',
        body: { id: 1, name: 'Alice' }
      };
    };

    const result = await engine.run({
      flowItem,
      executeRequest,
      onEvent
    });

    expect(result.status).toBe('success');
    expect(result.executedNodes).toEqual(['start-1', 'req-login', 'req-profile', 'end-1']);
    expect(result.context.token).toBe('jwt-abc-123');
    expect(result.context.envName).toBe('staging');

    // Verify token was passed downstream into context when executing req-profile
    expect(requestsExecuted[1].itemRef).toBe('users/profile.bru');
    expect(requestsExecuted[1].context.token).toBe('jwt-abc-123');

    // Check events fired
    expect(events.some((e) => e.type === 'flow:started')).toBe(true);
    expect(events.some((e) => e.type === 'flow:finished')).toBe(true);
  });

  it('should branch correctly based on condition evaluation', async () => {
    const flowItem = {
      name: 'Branching Flow',
      flow: {
        nodes: [
          { id: 'start-1', type: 'start' },
          {
            id: 'req-auth',
            type: 'request',
            data: {
              itemRef: 'auth/login.bru'
            }
          },
          {
            id: 'cond-1',
            type: 'condition',
            data: { expression: 'res.status === 200' }
          },
          { id: 'req-success-path', type: 'request', data: { itemRef: 'success.bru' } },
          { id: 'req-failure-path', type: 'request', data: { itemRef: 'failure.bru' } }
        ],
        edges: [
          { id: 'e1', source: 'start-1', target: 'req-auth' },
          { id: 'e2', source: 'req-auth', target: 'cond-1' },
          { id: 'e3', source: 'cond-1', sourceHandle: 'true', target: 'req-success-path' },
          { id: 'e4', source: 'cond-1', sourceHandle: 'false', target: 'req-failure-path' }
        ]
      }
    };

    // Case 1: Status 200 -> should take req-success-path
    const executeSuccess = async () => ({ status: 200, body: {} });
    const resSuccess = await engine.run({
      flowItem,
      executeRequest: executeSuccess
    });
    expect(resSuccess.executedNodes).toEqual(['start-1', 'req-auth', 'cond-1', 'req-success-path']);

    // Case 2: Status 401 -> should take req-failure-path
    const executeFailure = async () => ({ status: 401, body: {} });
    const resFailure = await engine.run({
      flowItem,
      executeRequest: executeFailure
    });
    expect(resFailure.executedNodes).toEqual(['start-1', 'req-auth', 'cond-1', 'req-failure-path']);
  });

  it('should support abortion via AbortSignal', async () => {
    const flowItem = {
      flow: {
        nodes: [
          { id: 'start-1', type: 'start' },
          { id: 'delay-1', type: 'delay', data: { durationMs: 5000 } }
        ],
        edges: [{ id: 'e1', source: 'start-1', target: 'delay-1' }]
      }
    };

    const controller = new AbortController();
    setTimeout(() => controller.abort(), 50);

    const result = await engine.run({
      flowItem,
      abortSignal: controller.signal
    });

    expect(result.status).toBe('cancelled');
    expect(result.error).toContain('aborted');
  });
});
