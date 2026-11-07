import type { Item as BrunoItem } from '@usebruno/schema-types/collection/item';
import { uuid } from '../../../utils';

export interface FlowFile {
  info: { name?: string; type: 'flow'; seq?: number; tags?: string[] };
  flow?: BrunoItem['flow'];
  settings?: BrunoItem['settings'];
}

const parseFlow = (file: FlowFile): BrunoItem => ({
  uid: uuid(),
  type: 'flow',
  name: file.info.name || 'Flow',
  seq: typeof file.info.seq === 'number' ? file.info.seq : 1,
  tags: Array.isArray(file.info.tags) ? file.info.tags : [],
  request: null,
  settings: file.settings || null,
  flow: file.flow || { nodes: [], edges: [] },
  fileContent: null,
  root: null,
  items: [],
  examples: [],
  filename: null,
  pathname: null
});

export default parseFlow;
