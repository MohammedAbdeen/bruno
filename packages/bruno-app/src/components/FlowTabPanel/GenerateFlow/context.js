import { flattenItems } from 'utils/collections';

export const buildFlowRequests = (collection) => flattenItems(collection.items || [])
  .filter((item) => !item.isTransient && item.pathname && ['http-request', 'graphql-request'].includes(item.type))
  .map((item) => {
    // Only variable names leave the renderer from request definitions.
    const request = item.request || {};
    const inputs = [...JSON.stringify({ url: request.url, body: request.body, headers: request.headers, params: request.params, auth: request.auth })
      .matchAll(/\{\{\s*([^{}]+?)\s*\}\}/g)].map((match) => match[1]);
    return { pathname: item.pathname, name: item.name, method: request.method, inputs,
      responseData: item.response?.data ?? item.response?.body };
  });
