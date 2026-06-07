/** cross_tab_ref 组件级依赖工具：源依赖提取 + 拓扑排序（Kahn）+ 环检测。与后端 CrossTabComponentOrder 对齐。 */

interface FormulaLike { expression?: any[]; }

/** 从组件 formulas 收集所有 cross_tab_ref 的 source（去重，保留首次出现序）。 */
export function extractSourceRefs(formulas?: FormulaLike[]): string[] {
  const refs: string[] = [];
  const seen = new Set<string>();
  for (const f of formulas ?? []) {
    for (const t of f?.expression ?? []) {
      if (t?.type === 'cross_tab_ref' && t.source && !seen.has(t.source)) {
        seen.add(t.source);
        refs.push(t.source);
      }
    }
  }
  return refs;
}

/**
 * Kahn 拓扑序；保留输入相对序；有环 throw。
 * @param ids 组件标识（按出现序）
 * @param deps 组件标识 → 它依赖的源组件标识[]（仅 ids 内的计入入度）
 */
export function topoOrderComponents(ids: string[], deps: Record<string, string[]>): string[] {
  const idSet = new Set(ids);
  const indeg: Record<string, number> = {};
  ids.forEach((c) => (indeg[c] = 0));
  ids.forEach((c) => (deps[c] ?? []).forEach((d) => { if (idSet.has(d)) indeg[c]++; }));
  const queue = ids.filter((c) => indeg[c] === 0);
  const order: string[] = [];
  while (queue.length) {
    const c = queue.shift()!;
    order.push(c);
    ids.forEach((o) => {
      if ((deps[o] ?? []).includes(c)) {
        indeg[o]--;
        if (indeg[o] === 0) queue.push(o);
      }
    });
  }
  if (order.length !== ids.length) throw new Error('cross_tab_ref 组件循环引用');
  return order;
}
