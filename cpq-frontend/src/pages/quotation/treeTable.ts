/** 单行的邻接表输入:id=本行标识(料号),parent=父标识(父料号);空串/null 视为无。 */
export interface TreeNodeInput {
  id: string | null;
  parent: string | null;
}

export interface TreeLayout {
  /** 重排后的原始行下标序列(DFS,子行紧跟父行;同级保持原序) */
  order: number[];
  /** 原始行下标 → 缩进层级(根=0) */
  depthByIndex: Record<number, number>;
  /** 含子节点的原始行下标(显示折叠箭头) */
  hasChildren: Set<number>;
  /** 原始行下标 → 父行下标(根=null);折叠隐藏判定用 */
  parentIndexByIndex: Record<number, number | null>;
}

/**
 * 把平铺行的 (id, parent) 邻接表重排成树布局(纯展示)。
 * 不改变行集合:order.length === nodes.length(永不丢行)。
 */
export function buildTreeRows(nodes: TreeNodeInput[]): TreeLayout {
  const n = nodes.length;

  // 规则4:同 id 多行 → 第一条声明者胜
  const idToIndex = new Map<string, number>();
  for (let i = 0; i < n; i++) {
    const id = nodes[i].id;
    if (id != null && id !== '' && !idToIndex.has(id)) idToIndex.set(id, i);
  }

  // 规则1:parent 空、或匹配不到 id、或指向自身 → 根(null)
  const parentIndexByIndex: Record<number, number | null> = {};
  for (let i = 0; i < n; i++) {
    const p = nodes[i].parent;
    if (p == null || p === '') {
      parentIndexByIndex[i] = null;
      continue;
    }
    const pi = idToIndex.get(p);
    parentIndexByIndex[i] = pi == null || pi === i ? null : pi;
  }

  // 规则3:环检测 — 环上行全部降级为根 + warn(不死循环)
  const onCycle = new Set<number>();
  for (let i = 0; i < n; i++) {
    if (onCycle.has(i)) continue;
    const pathPos = new Map<number, number>();
    const path: number[] = [];
    let cur: number | null = i;
    while (cur != null) {
      if (onCycle.has(cur)) break;
      if (pathPos.has(cur)) {
        // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
        for (let k = pathPos.get(cur)!; k < path.length; k++) onCycle.add(path[k]);
        break;
      }
      pathPos.set(cur, path.length);
      path.push(cur);
      cur = parentIndexByIndex[cur];
    }
  }
  if (onCycle.size > 0) {
    // eslint-disable-next-line no-console
    console.warn(`[treeTable] 检测到父子成环,${onCycle.size} 行降级为根平铺显示`);
    for (const i of onCycle) parentIndexByIndex[i] = null;
  }

  // 规则2/6:按 parent 聚子,子行保持原始下标顺序
  const childrenByParent = new Map<number | null, number[]>();
  for (let i = 0; i < n; i++) {
    const p = parentIndexByIndex[i];
    if (!childrenByParent.has(p)) childrenByParent.set(p, []);
    // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
    childrenByParent.get(p)!.push(i);
  }

  const order: number[] = [];
  const depthByIndex: Record<number, number> = {};
  const hasChildren = new Set<number>();
  const visit = (idx: number, depth: number): void => {
    order.push(idx);
    depthByIndex[idx] = depth;
    const kids = childrenByParent.get(idx);
    if (kids && kids.length > 0) {
      hasChildren.add(idx);
      for (const k of kids) visit(k, depth + 1);
    }
  };
  // eslint-disable-next-line @typescript-eslint/no-non-null-assertion
  for (const r of childrenByParent.get(null) ?? []) visit(r, 0);

  return { order, depthByIndex, hasChildren, parentIndexByIndex };
}
