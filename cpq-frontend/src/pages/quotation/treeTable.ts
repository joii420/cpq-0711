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

/**
 * 行是否因祖先折叠而隐藏:沿 parentIndex 链上溯,任一祖先的 nodeKey ∈ collapsed → 隐藏。
 */
export function isTreeRowHidden(
  index: number,
  parentIndexByIndex: Record<number, number | null>,
  nodeKeyByIndex: Record<number, string>,
  collapsed: Set<string>,
): boolean {
  let p = parentIndexByIndex[index];
  while (p != null) {
    const key = nodeKeyByIndex[p];
    if (key != null && collapsed.has(key)) return true;
    p = parentIndexByIndex[p];
  }
  return false;
}

/**
 * 解析某行某字段用于建树的归一化键(字符串)。
 * 优先级对齐 ComponentCell 取值:driver 行级 basicDataValues[lookupKey] → row[name]。
 * idField/parentField 据设计来自 driver/SQL 视图(BASIC_DATA / DATA_SOURCE.BNF_PATH)。
 */
export function resolveTreeKey(
  field: { name?: string; field_type?: string; basic_data_path?: string; datasource_binding?: any },
  row: Record<string, any>,
  basicDataValues: Record<string, any> | undefined,
  bnfLookup: (path: string) => string,
): string | null {
  const norm = (v: any): string | null => {
    if (v == null || v === '') return null;
    if (Array.isArray(v)) return v.length > 0 ? norm(v[0]) : null;
    if (typeof v === 'object') return null;
    const s = String(v).trim();
    return s === '' ? null : s;
  };
  let path: string | undefined;
  if (field.field_type === 'BASIC_DATA') path = field.basic_data_path;
  else if (field.field_type === 'DATA_SOURCE' && field.datasource_binding?.type === 'BNF_PATH') {
    path = field.datasource_binding.bnf_path;
  }
  if (path && basicDataValues) {
    const lk = bnfLookup(path);
    if (Object.prototype.hasOwnProperty.call(basicDataValues, lk)) {
      const got = norm(basicDataValues[lk]);
      if (got != null) return got;
    }
  }
  return norm(field.name ? row[field.name] : null);
}

export interface TreeRenderRow<T> {
  item: T;
  originalIndex: number;
  depth: number;
  hasChildren: boolean;
  nodeKey: string;
  parentIndex: number | null;
}

export interface TreeLayoutResult<T> {
  rows: TreeRenderRow<T>[];
  parentIndexByIndex: Record<number, number | null>;
  nodeKeyByIndex: Record<number, string>;
}

/**
 * 把渲染描述符数组按 treeConfig 重排成树渲染行。
 * @param keyPrefix nodeKey 前缀(用 componentId,保证全局唯一 + tab 切换稳定)
 */
export function layoutTreeRows<T>(
  items: T[],
  idOf: (item: T, index: number) => string | null,
  parentOf: (item: T, index: number) => string | null,
  keyPrefix: string,
): TreeLayoutResult<T> {
  const nodes: TreeNodeInput[] = items.map((it, i) => ({ id: idOf(it, i), parent: parentOf(it, i) }));
  const layout = buildTreeRows(nodes);
  const nodeKeyByIndex: Record<number, string> = {};
  for (let i = 0; i < items.length; i++) {
    const id = nodes[i].id;
    nodeKeyByIndex[i] = `${keyPrefix}::${id != null ? id : `#${i}`}`;
  }
  const rows: TreeRenderRow<T>[] = layout.order.map((origIdx) => ({
    item: items[origIdx],
    originalIndex: origIdx,
    depth: layout.depthByIndex[origIdx] ?? 0,
    hasChildren: layout.hasChildren.has(origIdx),
    nodeKey: nodeKeyByIndex[origIdx],
    parentIndex: layout.parentIndexByIndex[origIdx] ?? null,
  }));
  return { rows, parentIndexByIndex: layout.parentIndexByIndex, nodeKeyByIndex };
}
