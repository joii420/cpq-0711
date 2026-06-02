import { useCallback, useState } from 'react';

/**
 * 树表折叠态(会话内,不持久化;刷新/重进恢复默认)。
 *
 * 模型:只存"用户显式翻转过的 nodeKey"。某节点的"有效折叠"=
 *   defaultExpanded=true  → 翻转过即折叠
 *   defaultExpanded=false → 未翻转即折叠(默认全折叠)
 * 这样顶层无需预知 id 即可表达两种默认,nodeKey 含 componentId 前缀全局唯一。
 */
export function useTreeCollapse() {
  const [toggled, setToggled] = useState<Set<string>>(() => new Set());

  const isCollapsed = useCallback(
    (nodeKey: string, defaultExpanded: boolean) =>
      defaultExpanded ? toggled.has(nodeKey) : !toggled.has(nodeKey),
    [toggled],
  );

  const toggle = useCallback((nodeKey: string) => {
    setToggled((prev) => {
      const next = new Set(prev);
      if (next.has(nodeKey)) next.delete(nodeKey);
      else next.add(nodeKey);
      return next;
    });
  }, []);

  /** 给定可见行的 defaultExpanded,算出当前"有效折叠"的 nodeKey 集合(供 isTreeRowHidden 用)。 */
  const collapsedSet = useCallback(
    (nodeKeys: string[], defaultExpanded: boolean): Set<string> => {
      const s = new Set<string>();
      for (const k of nodeKeys) if (isCollapsed(k, defaultExpanded)) s.add(k);
      return s;
    },
    [isCollapsed],
  );

  return { isCollapsed, toggle, collapsedSet };
}
