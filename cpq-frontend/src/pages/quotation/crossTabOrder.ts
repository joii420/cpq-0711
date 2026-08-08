/** cross_tab_ref 组件级依赖工具：源依赖提取 + 拓扑排序（Kahn）+ 环检测。与后端 CrossTabComponentOrder 对齐。 */

interface FormulaLike { expression?: any[]; }

/** 参与拓扑的一个页签字段（供 buildComponentDeps 判定被引用列是否顺序敏感）。 */
interface FieldLike { name?: string; key?: string; field_type?: string; fieldType?: string }

/** 参与拓扑的一个页签（仅 NORMAL；调用方自行过滤 SUBTOTAL/EXCEL）。 */
export interface TabDepInput {
  /** 图中的节点键 = componentId || componentCode || tabName（与调用方 ids 同源）。 */
  cid: string;
  /** componentCode，解析 component_subtotal.component_code。 */
  code?: string;
  /** 页签名，component_code 缺失时的回退解析键。 */
  tabName?: string;
  /** 该页签 formulas。 */
  formulas?: FormulaLike[];
  /** 该页签 fields（判被引用列是否顺序敏感）。 */
  fields?: FieldLike[];
}

/** component_subtotal 引用明细：目标页签标识 + 被引用列名 + 是否整页签合计。 */
interface SubtotalRef { ref: string; column: string; tabTotal: boolean; }

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

// ── repair-0808：component_subtotal 依赖边的「列粒度」精化（镜像后端 CrossTabComponentOrder） ───────────────────

/** 扫描 formulas，收集 component_subtotal 引用明细（目标标识 + 列名 + 是否整页签合计）。 */
function extractSubtotalRefDetails(formulas?: FormulaLike[]): SubtotalRef[] {
  const out: SubtotalRef[] = [];
  for (const f of formulas ?? []) {
    for (const t of f?.expression ?? []) {
      if (t?.type !== 'component_subtotal') continue;
      const code: string = t.component_code ?? '';
      const tab: string = t.tab_name ?? '';
      const ref = code || tab;
      if (!ref) continue;
      const column: string = t.value ?? '';
      const tabTotal = t.is_tab_total === true || column === '__amount_total__';
      out.push({ ref, column, tabTotal });
    }
  }
  return out;
}

/** 公式类字段（FORMULA 及未来的 *_FORMULA 变体，如 LIST_FORMULA）→ 值由公式产生，顺序敏感。 */
function isFormulaType(fieldType: string): boolean {
  return fieldType === 'FORMULA' || fieldType.endsWith('_FORMULA');
}

/** 兼容 camelCase（快照 structure）与 snake_case（component.fields，enrich 后），对齐后端 fieldTypeOf。 */
function fieldTypeOf(f: FieldLike): string {
  if (Object.prototype.hasOwnProperty.call(f ?? {}, 'fieldType')) return (f.fieldType as any) ?? '';
  return (f as any)?.field_type ?? '';
}

function fieldNameOf(f: FieldLike): string {
  const n = f?.name ?? '';
  return n !== '' ? n : (f?.key ?? '');
}

/** 被引用列的值是否取决于页签计算次序（即是否需要为它建依赖边）。保守优先：判不出就建边。 */
function isOrderSensitiveColumn(targetFields: FieldLike[] | undefined, sr: SubtotalRef): boolean {
  if (sr.tabTotal) return true;                                    // 整页签合计含全部公式列
  if (!sr.column) return true;                                     // 列名缺失 → 保守
  if (!Array.isArray(targetFields)) return true;                   // fields 不可读 → 保守
  for (const f of targetFields) {
    if (fieldNameOf(f) !== sr.column) continue;
    return isFormulaType(fieldTypeOf(f));
  }
  return true;                                                      // 查无此列 → 保守
}

/**
 * 构建组件级依赖图（cross_tab_ref 全量 + component_subtotal 按列粒度精化）。与后端
 * `CrossTabComponentOrder.buildComponentDeps` 逐条镜像（repair-0808 / QT-20260807-0146）。
 *
 * 为什么要按列判定：拓扑序只解决「谁先算」。一列的值是否取决于计算次序，取决于它是不是
 * 公式列——INPUT_NUMBER / BASIC_DATA / DATA_SOURCE 等列的值在行迭代之前就已定死，无论页签
 * 谁先算都不会变。给这类引用建边收益为零，却会凭空造出反向边，折成页签粒度后可能成环
 * （"产品 ⇄ 物料"假环，见需求文档 §5）。
 *
 * 保守优先：只有能确证被引用列非公式列时才省略边；整页签合计 / 列名缺失 / 目标 fields
 * 不可读 / 查无此列 —— 一律照旧建边（宁可多排一次序，不可算错值，守住 QT-1743 的修复）。
 *
 * @param tabs 参与拓扑的页签（仅 NORMAL；调用方需自行过滤 SUBTOTAL/EXCEL）
 * @returns cid → 依赖的 cid[]（可直接喂 topoOrderComponents）
 */
export function buildComponentDeps(tabs: TabDepInput[]): Record<string, string[]> {
  const refToCid = new Map<string, string>();
  const fieldsByCid = new Map<string, FieldLike[] | undefined>();
  for (const t of tabs) {
    const cid = t.cid;
    if (!cid) continue;
    refToCid.set(cid, cid);
    if (t.code) refToCid.set(t.code, cid);
    if (t.tabName) refToCid.set(t.tabName, cid);
    fieldsByCid.set(cid, t.fields);
  }
  const deps: Record<string, string[]> = {};
  for (const t of tabs) {
    // cross_tab_ref：按行取源组件已算行，恒为顺序依赖，不做列粒度豁免
    const d = new Set<string>(extractSourceRefs(t.formulas));
    for (const sr of extractSubtotalRefDetails(t.formulas)) {
      const tcid = refToCid.get(sr.ref);
      // 卡片外引用（解析不到）不入图；自引用由引擎内两阶段处理（B2），不建边
      if (!tcid || tcid === t.cid) continue;
      if (isOrderSensitiveColumn(fieldsByCid.get(tcid), sr)) d.add(tcid);
    }
    deps[t.cid] = [...d];
  }
  return deps;
}
