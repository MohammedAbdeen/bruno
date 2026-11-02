import StartNode from './StartNode';
import RequestNode from './RequestNode';
import ConditionNode from './ConditionNode';
import DelayNode from './DelayNode';
import ScriptNode from './ScriptNode';
import EndNode from './EndNode';

export const nodeTypes = {
  start: StartNode,
  request: RequestNode,
  condition: ConditionNode,
  delay: DelayNode,
  script: ScriptNode,
  end: EndNode
};
