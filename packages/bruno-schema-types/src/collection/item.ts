import type { UID } from '../common';
import type { Request } from '../requests';
import type { Example } from './examples';
import type { FolderRoot } from './folder';

export type ItemType
  = | 'http-request'
    | 'graphql-request'
    | 'folder'
    | 'js'
    | 'app'
    | 'grpc-request'
    | 'ws-request'
    | 'flow';

export type FlowNodeType
  = | 'start'
    | 'request'
    | 'condition'
    | 'delay'
    | 'script'
    | 'end'
    | 'for-each'
    | 'sub-flow'
    | 'assert'
    | 'data-source';

export interface FlowNodePosition {
  x: number;
  y: number;
}

export interface FlowNode {
  id: string;
  type: FlowNodeType;
  position?: FlowNodePosition | null;
  data?: Record<string, any> | null;
}

export interface FlowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string | null;
  targetHandle?: string | null;
  label?: string | null;
}

export interface FlowViewport {
  x: number;
  y: number;
  zoom: number;
}

export interface Flow {
  nodes?: FlowNode[];
  edges?: FlowEdge[];
  viewport?: FlowViewport | null;
  config?: Record<string, any> | null;
}

export interface HttpItemSettings {
  encodeUrl?: boolean | null;
  followRedirects?: boolean | null;
  maxRedirects?: number | null;
  timeout?: number | 'inherit' | null;
  forwardAuthorizationHeader?: boolean | null;
  omitHeaders?: string[] | null;
}

export interface WebSocketItemSettings {
  settings?: {
    timeout?: number | null;
    keepAliveInterval?: number | null;
  } | null;
}

export type ItemSettings = HttpItemSettings | WebSocketItemSettings | null;

export interface App {
  code?: string | null;
  enabled?: boolean | null;
}

export interface Item {
  uid: UID;
  type: ItemType;
  seq?: number | null;
  name: string;
  description?: string | null;
  tags?: string[] | null;
  request?: Request | null;
  flow?: Flow | null;
  settings?: ItemSettings;
  app?: App | null;
  fileContent?: string | null;
  root?: FolderRoot | null;
  items?: Item[] | null;
  examples?: Example[] | null;
  filename?: string | null;
  pathname?: string | null;
}
