import type { GlobalVariableDefinition } from '../services/globalVariableService';
import { compileGlobalVariableTokenForRow } from '../services/globalVariableService';
import { evaluateArithmetic } from './precision';

export type { GlobalVariableDefinition } from '../services/globalVariableService';

export interface ExpressionToken {
  type: 'field' | 'operator' | 'bracket_open' | 'bracket_close' | 'number' | 'component_subtotal' | 'product_attribute' | 'quotation_field' | 'path' | 'global_variable' | 'previous_row_subtotal' | 'datasource_field' | 'cross_tab_ref' | 'b_field' | 'tree_ref' | 'tree_attr';
  value?: string;
  label?: string;
  component_code?: string;
  tab_name?: string;
  attribute_name?: string;
  /** datasource_field 专用 (K1): 引用同行 DATA_SOURCE 字段名, 求值期从 fieldValues 取 */
  name?: string;
  /** path token 专用:BNF 路径表达式(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量) */
  path?: string;
  /** global_variable 专用:注册表 code */
  code?: string;
  /** global_variable 专用:静态 key (列名→字面值) */
  key_values?: Record<string, any>;
  /** global_variable 专用:动态 key (列名→同行字段名, 求值时取行数据) */
  key_field_refs?: Record<string, string>;
  /**
   * previous_row_subtotal 专用 — 引用同 driver 组件上一行的 is_subtotal 字段值.
   *
   * <p>典型用法: 工序组件的累加公式 — `本工序小计 = 上道工序小计 / 成材率 + 单价`.
   * <p>行 0 时上一行不存在, ProductCard 求值时按 fallback_component_code 取该组件的
   * 跨组件 subtotal 作为初值(如工序组件行 0 fallback 到元素 Tab 的小计).
   * 若未配置 fallback_component_code,则行 0 默认返 0.
   */
  fallback_component_code?: string;
  /** cross_tab_ref 专用 */
  source?: string;
  sourceLabel?: string;
  target?: string;
  match?: Array<{ a: string; b: string }>;
  agg?: 'NONE' | 'SUM' | 'AVG' | 'COUNT' | 'MAX' | 'MIN';
  /** cross_tab_ref 目标公式（非空时优先于 target）；其内 field=A列, b_field=B列 */
  targetExpr?: ExpressionToken[];
  /** v1 多 source 有序链 (最细→更粗); source 镜像为最细 sources[0] */
  sources?: Array<{ source: string; sourceLabel?: string; match: Array<{ a: string; b: string }> }>;
  /**
   * v2 KSUM: true = 按宿主结果行键塌缩成宿主粒度标量 (区别外层 join-set 聚合).
   * KSUM/KCOUNT 空集 → 0 (静默, I-1 决策 K).
   * KAVG/KMAX/KMIN 空集 → null → 整外层表达式塌 0 + outDiag.crossTabError (I-2 决策 K).
   * 缺省 false.
   */
  projectToHostKey?: boolean;
  /** SUMIF 族：cross_tab_ref 的可选附加过滤条件（布尔树）。缺省 = 不过滤。 */
  predicate?: ConditionPredicate | null;
  // ---- tree_ref 专用字段（task-0803：BOM 树父子取值。本任务仅声明类型，求值逻辑见 Task 7）----
  /**
   * tree_ref 专用：引用方向。
   * - 'PARENT' = PGET，子行取父行的值（agg 恒为 'NONE'）。
   * - 'CHILD'  = C* 族，父行取其「直接子」行的聚合值（agg 取 'SUM'|'AVG'|'MAX'|'MIN'|'COUNT'，复用本接口既有的 agg 字段）。
   */
  dir?: 'PARENT' | 'CHILD';
  // ---- tree_attr 专用字段（task-0803：BOM 树属性。本任务仅声明类型，求值逻辑见 Task 7）----
  /**
   * tree_attr 专用：树属性名。
   * - 'LVL'     = 当前行在树中的层级（根为 0，逐层 +1）。
   * - 'IS_LEAF' = 当前行是否为叶子节点（布尔）。
   * - 'IS_ROOT' = 当前行是否为根节点（布尔）。
   */
  attr?: 'LVL' | 'IS_LEAF' | 'IS_ROOT';
}

// ── predicate 类型 + 求值器（与后端 ConditionPredicateEvaluator 逐字一致） ──

export type PredicateOperand =
  | { kind: 'sourceField'; field: string }
  | { kind: 'hostField'; field: string }
  | { kind: 'literal'; value: string };

export type ConditionPredicate =
  | { bool: 'AND' | 'OR'; children: ConditionPredicate[] }
  | { op: '=' | '!=' | '<>' | '>' | '<' | '>=' | '<='; lhs: PredicateOperand; rhs: PredicateOperand };

/** 与后端 ConditionPredicateEvaluator 逐字一致。arow=source 行, hostRow=宿主行。 */
export function evalPredicate(
  p: ConditionPredicate | null | undefined,
  arow: Record<string, any>,
  hostRow: Record<string, any>,
): boolean {
  if (p == null) return true;
  if ('bool' in p) {
    return p.bool === 'AND'
      ? p.children.every((c) => evalPredicate(c, arow, hostRow))
      : p.children.some((c) => evalPredicate(c, arow, hostRow));
  }
  const resolve = (o: PredicateOperand) =>
    o.kind === 'sourceField' ? arow?.[o.field]
    : o.kind === 'hostField' ? hostRow?.[o.field]
    : o.value;
  const lv = resolve(p.lhs), rv = resolve(p.rhs);
  const blank = (x: any) => x == null || String(x).trim() === '';
  // 注：数字解析用 Number()，与后端 Double.valueOf 在 0x../类型后缀/"NaN" 等病态输入上口径略异；CPQ 业务数据(金额/数量/类型名)不会出现这些，沿用既有 keyEq/valEquals 容差，不额外收紧。
  const num = (x: any) => { const n = Number(String(x).trim()); return isNaN(n) ? null : n; };
  const eq = (): boolean => {
    if (blank(lv) || blank(rv)) return false;
    const na = num(lv), nb = num(rv);
    if (na !== null && nb !== null) return na === nb;
    return String(lv).trim() === String(rv).trim();
  };
  const cmp = (): number | null => {
    if (blank(lv) || blank(rv)) return null;
    const na = num(lv), nb = num(rv);
    if (na === null || nb === null) return null;
    return na < nb ? -1 : na > nb ? 1 : 0;
  };
  switch (p.op) {
    case '=': return eq();
    case '!=': case '<>': return !eq();
    case '>': { const c = cmp(); return c !== null && c > 0; }
    case '<': { const c = cmp(); return c !== null && c < 0; }
    case '>=': { const c = cmp(); return c !== null && c >= 0; }
    case '<=': { const c = cmp(); return c !== null && c <= 0; }
  }
}

// ── task-0803 Task 7：BOM 树父子取值求值上下文（tree_ref / tree_attr） ──
//
// 与后端 FormulaCalculator.TreeEvalContext / RowContext.tree 镜像：undefined = 无树上下文
// （非 BOM 页签 / 拿不到树关系）→ tree_ref/tree_attr 恒返 0（需求 §4.3.8 闸⑤）。
//
// 之所以不复用 evaluateExpression 现有的散装位置参数，而是打包成一个上下文对象：
// PGET/C* 求值需要在【另一行】（父行/子行）的上下文里递归求值 targetExpr —— 该行有自己的
// fieldValues/basicDataValues/currentRow，用位置参数表达"切到另一行"会让签名进一步爆炸。
export interface TreeEvalContext {
  /** 当前正在求值的行下标（对齐 rowBundles/rawPresent 数组下标）。 */
  rowIndex: number;
  /** 直接父行下标；-1 = 无父（根行 / 父已墓碑 / 父不在行集中，§11.5 G5 统一按"无父"处理）。 */
  parentOf: (rowIndex: number) => number;
  /** 直接子行下标（不含孙辈；调用方在构造前已排除墓碑行，故这里天然不含）。 */
  childrenOf: (rowIndex: number) => number[];
  lvl: (rowIndex: number) => number;
  isRoot: (rowIndex: number) => boolean;
  isLeaf: (rowIndex: number) => boolean;
  /** 每行已解析的求值素材，供 targetExpr 在目标行（父/子）上下文求值。 */
  rowBundles: Array<{
    fieldValues: Record<string, number>;
    basicDataValues?: Record<string, any>;
    currentRow?: Record<string, any>;
  }>;
  /**
   * 每行「引用列是否有值」判定用的存在性标记（field 名 → 是否非空，非 blank）。
   * 🚨 数值 0 也是"有值"——调用方必须存布尔存在性，不能存原始值再用 `if(!v)` 判断（0 是 falsy 陷阱）。
   */
  rawPresent: Array<Record<string, boolean>>;
  /** 公式列名集合 —— 判据 6：被引用列若是公式列，恒视为有值。 */
  formulaColumns: Set<string>;
  pathCache?: Record<string, number>;
  partNo?: string;
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
}

/** targetExpr 内所有顶层 field token 的列名（判「有值」用；targetExpr 内不允许嵌套 tree_ref，故只需扫顶层）。 */
function collectTreeRefFieldNames(expr: ExpressionToken[] | undefined): Set<string> {
  const out = new Set<string>();
  if (!expr) return out;
  for (const t of expr) {
    if (t.type === 'field' && t.value) out.add(t.value);
  }
  return out;
}

/**
 * 需求 §4.3.4「有值」判据（与后端 FormulaCalculator.hasValueForAgg 逐位一致）：
 * 1. targetExpr 无 field token（纯常量/树属性）→ 有值（判据 5）。
 * 2. 引用的列是公式列 → 恒有值（判据 6）。
 * 3. 至少一个引用列在该行有非空原始值 → 有值（判据 3；数值 0 算有值，判据 4）。
 * 4. 全部引用列取不到值 → 无值（判据 2）。
 *
 * 🚨 判据 4 的 falsy 陷阱：这里读 `raw[n] === true`（显式布尔相等），不用 `if (raw[n])`——
 * 后者会把"数值 0 存在"错误短路成 falsy，导致 CMIN 把 0 值子行误判为无值。
 */
function treeHasValue(ctx: TreeEvalContext, rowIdx: number, names: Set<string>): boolean {
  if (names.size === 0) return true; // 判据 5
  const raw = ctx.rawPresent[rowIdx] ?? {};
  for (const n of names) {
    if (ctx.formulaColumns.has(n)) return true; // 判据 6：公式列恒有值
    if (raw[n] === true) return true;           // 判据 3/4：显式布尔判断，0 也算有值
  }
  return false; // 判据 2：全部取不到值
}

/** C* 族聚合（SUM/AVG/MAX/MIN/COUNT）。与 cross_tab_ref 聚合口径一致（纯 JS number 精度，非 Decimal）。 */
function aggregateTreeNums(agg: string, nums: number[]): number {
  if (nums.length === 0) return 0;
  switch ((agg || 'SUM').toUpperCase()) {
    case 'SUM': return nums.reduce((s, x) => s + x, 0);
    case 'AVG': return nums.reduce((s, x) => s + x, 0) / nums.length;
    case 'MAX': return Math.max(...nums);
    case 'MIN': return Math.min(...nums);
    case 'COUNT': return nums.length;
    default: return 0;
  }
}

/**
 * {@code tree_ref} 求值：dir=PARENT 取直接父行（PGET）；dir=CHILD 聚合直接子行（C* 族）。
 * 边界口径（需求 §4.3.3，全部返 0，无例外）：无树上下文 / 根行 PGET / 叶子行 C* / 子行全部无值 → 0。
 */
function evalTreeRefToken(token: ExpressionToken, ctx: TreeEvalContext | undefined): number {
  if (!ctx || ctx.rowIndex < 0 || ctx.rowIndex >= ctx.rowBundles.length) return 0;
  const targetExpr = token.targetExpr;
  if (!targetExpr || targetExpr.length === 0) return 0;

  if (token.dir === 'PARENT') {
    const p = ctx.parentOf(ctx.rowIndex);
    if (p < 0) return 0; // 根行（或父已墓碑/不在行集中）→ 0
    const pb = ctx.rowBundles[p];
    return evaluateExpression(
      targetExpr, pb.fieldValues, undefined, undefined, undefined,
      ctx.pathCache, ctx.partNo, pb.basicDataValues, undefined, ctx.globalVariableDefs,
      pb.currentRow, undefined, undefined, { ...ctx, rowIndex: p },
    );
  }
  if (token.dir !== 'CHILD') return 0;

  const kids = ctx.childrenOf(ctx.rowIndex);
  if (kids.length === 0) return 0; // 叶子行 → 0

  const names = collectTreeRefFieldNames(targetExpr);
  const nums: number[] = [];
  for (const c of kids) {
    if (!treeHasValue(ctx, c, names)) continue; // 空值不参与聚合
    const cb = ctx.rowBundles[c];
    nums.push(evaluateExpression(
      targetExpr, cb.fieldValues, undefined, undefined, undefined,
      ctx.pathCache, ctx.partNo, cb.basicDataValues, undefined, ctx.globalVariableDefs,
      cb.currentRow, undefined, undefined, { ...ctx, rowIndex: c },
    ));
  }
  if (nums.length === 0) return 0; // 子行全部无值 → 0
  return aggregateTreeNums(token.agg ?? 'SUM', nums);
}

/** {@code tree_attr} 求值：层级 / 是否叶子 / 是否根。无树上下文 → 0。 */
function evalTreeAttrToken(token: ExpressionToken, ctx: TreeEvalContext | undefined): number {
  if (!ctx || ctx.rowIndex < 0) return 0;
  switch (token.attr) {
    case 'LVL': return ctx.lvl(ctx.rowIndex);
    case 'IS_LEAF': return ctx.isLeaf(ctx.rowIndex) ? 1 : 0;
    case 'IS_ROOT': return ctx.isRoot(ctx.rowIndex) ? 1 : 0;
    default: return 0;
  }
}

/** 检测公式 token 数组中是否含 path 类型(决定走前端本地求值还是后端 API) */
export function hasPathToken(tokens: ExpressionToken[]): boolean {
  // V104: global_variable token 编译产物也是 BNF path, 共用 path resolver 流水线 (path cache 预热)
  return tokens?.some((t) => t.type === 'path' || t.type === 'global_variable') ?? false;
}

/**
 * 模块级 path 求值缓存,key = `${partNo}::${path}` → 数值。
 * 由 usePathFormulaCache hook 通过 setGlobalPathCache 异步写入,
 * evaluateExpression 在没有显式传 pathCache 参数时从此处读取(默认行为)。
 *
 * 这样设计是为了让所有现有 evaluateExpression 调用点不必改签名。
 * 缺点:跨页面切换需 hook 重写;实操中报价单是单页编辑,影响有限。
 */
let _globalPathCache: Record<string, number> = {};
let _globalPartNo: string | undefined = undefined;

export function setGlobalPathCache(cache: Record<string, number>) {
  _globalPathCache = cache;
}

export function setGlobalPathPartNo(partNo: string | undefined) {
  _globalPartNo = partNo;
}

export function getGlobalPathCache(): Record<string, number> {
  return _globalPathCache;
}

/**
 * 把 token 数组组装成 FormulaEngine 可解析的字符串(供后端 evaluate 端点使用)。
 * - field/component_subtotal/product_attribute/quotation_field → [字段名] / [tab.字段]
 * - path → {BNF 路径}
 * - operator/bracket/number → 字面量
 *
 * 后端 FormulaEngine 同时识别 [字段] 占位 + {路径} BNF 引用。
 */
export function tokensToExpressionString(tokens: ExpressionToken[]): string {
  const parts: string[] = [];
  for (const t of tokens) {
    switch (t.type) {
      case 'field':
        parts.push(`[${t.value ?? ''}]`);
        break;
      case 'operator':
        parts.push(t.value === '\u00d7' ? '*' : t.value === '\u00f7' ? '/' : (t.value ?? ''));
        break;
      case 'bracket_open':
        parts.push('(');
        break;
      case 'bracket_close':
        parts.push(')');
        break;
      case 'number':
        parts.push(t.value ?? '0');
        break;
      case 'component_subtotal':
        parts.push(`[${t.value ?? t.tab_name ?? ''}]`);
        break;
      case 'product_attribute':
        parts.push(`[${t.attribute_name ?? ''}]`);
        break;
      case 'quotation_field':
        parts.push(`[${t.value ?? ''}]`);
        break;
      case 'path':
        parts.push(`{${t.path ?? ''}}`);
        break;
      case 'global_variable':
        // 序列化沿用 path 形态; 后端 FormulaEngine 解 BNF path 已支持
        parts.push(`{${t.path ?? ''}}`);
        break;
      case 'cross_tab_ref':
        // cross_tab_ref 由前端卡片引擎本地求值，不经后端 evaluate 端点；
        // 此处产出占位 '0' 仅防止向后端序列化时生成非法表达式字符串。
        parts.push('0');
        break;
    }
  }
  return parts.join(' ');
}

export function evaluateExpression(
  tokens: ExpressionToken[],
  fieldValues: Record<string, number>,
  componentSubtotals?: Record<string, number>,
  productAttributes?: Record<string, number>,
  quotationFields?: Record<string, number>,
  /** path token 求值缓存,key = `${partNo}::${path}`(由 usePathFormulaCache 提供) */
  pathCache?: Record<string, number>,
  /** 当前 LineItem 的料号(供 path token 拼 cache key 使用) */
  partNo?: string,
  /**
   * Y1.5 driver 行级 BASIC_DATA 值（key = "{path}"）。
   * 提供时 path token 优先按当前行取，避免 driver 展开时多行共用第一行结果。
   */
  basicDataValues?: Record<string, any>,
  /**
   * previous_row_subtotal token 求值上下文 — 同 driver 组件上一行的 is_subtotal 字段值.
   * ProductCard 按 row_index 顺序遍历求值时, 把上一行的 subtotal 结果传入.
   * 行 0 时未传入: token 走 fallback_component_code 取跨组件 subtotal, 或 0.
   */
  previousRowSubtotal?: number,
  /**
   * 动态 key 全局变量运行时 path 重写所需的 GV 定义字典 (code → def).
   * 向后兼容: 老调用点不传时动态 key token 兜底返 0 (旧行为).
   */
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
  /**
   * 动态 key 全局变量运行时 path 重写所需的当前行字段值.
   * 向后兼容: 老调用点不传时动态 key token 兜底返 0 (旧行为).
   */
  currentRow?: Record<string, any>,
  /**
   * cross_tab_ref：同卡片已算行存储（组件标识→行表）。
   * key = source（组件标识/Tab 名），value = 该组件的已计算行数组。
   * 向后兼容: 老调用点不传时 cross_tab_ref token 返 0.
   */
  crossTabRows?: Record<string, Array<Record<string, any>>>,
  /**
   * 错误旁路（不改数值）：cross_tab_ref 命中多行 / 非数值聚合时,数值仍按既有逻辑归 0/null,
   * 但若调用方传入此袋,则把人类可读的错误原因写入 outDiag.crossTabError,
   * 供渲染层显示 ⚠ 错误态(替代静默 0)。向后兼容: 老调用不传 = 零破坏。
   */
  outDiag?: { crossTabError?: string },
  /**
   * task-0803 Task 7：BOM 树求值上下文 — tree_ref(PGET/C*)/tree_attr(层级/是否叶子/是否根) 求值用。
   * 不传（非 BOM 页签的既有调用点）= undefined → 两类 token 恒返 0，不影响既有行为。
   */
  treeCtx?: TreeEvalContext,
  /**
   * repair-0803：<b>宿主行</b>已算字段值（字段名 → 数值），供 {@code b_field} 在 {@code currentRow}
   * 键缺失时回落 —— 修复 targetExpr 内 b_field 引用宿主 FORMULA 字段恒取 0 的问题。
   *
   * <p>为什么不能复用 {@code fieldValues}：在 cross_tab_ref.targetExpr 子求值（见下方 {@code
   * evalRowExpr}）里，{@code fieldValues} 参数装的是<b>被聚合源页签当前行</b>的列（从 {@code ar}
   * 灌入的 {@code aFieldValues}），而 {@code b_field} 语义恒为「宿主行的列」。两者必须分开，
   * 否则 b_field 会串到源页签同名列上。与后端 {@code RowContext.hostFieldValues} 逐字对齐
   * （见 FormulaCalculator.java :72-83）。
   *
   * <p>顶层调用（{@code computeAllFormulas}）传那个随算随更新的 {@code fieldValues}（同引用，
   * 逐个公式算完即时更新，故 b_field 能取到刚算出的宿主公式值）；{@code cross_tab_ref} 递归求值时
   * 原样透传（不并入 {@code aFieldValues}）。不传（老调用点）= undefined → 回落分支取不到值 → 行为
   * 与修复前一致（零破坏）。
   */
  hostFieldValues?: Record<string, number>,
): number {
  // Build expression string from tokens
  let expr = '';
  for (const token of tokens) {
    switch (token.type) {
      case 'field':
        expr += (fieldValues[token.value!] ?? 0).toString();
        break;
      case 'b_field': {
        // repair-0803：宿主行原始值优先；键缺失时回落已算字段值（hostFieldValues）。
        // 理由：FORMULA 字段的结果只写回 fieldValues（computeAllFormulas 逐个公式算完后
        // `fieldValues[name] = val`），从不回填 currentRow；故不回落时，targetExpr 内引用
        // 本页签公式列恒取 0（静默少算）。与后端 FormulaCalculator `case "b_field"` 逐字对齐。
        // 键存在但为空串 '' = 用户显式置空 → 尊重置空、按 0 算，不回落（raw != null 即视为命中，
        // 与后端 `raw != null ? toNumber(raw) : hostFieldValues.get(n)` 口径对称；JS 里 `raw != null`
        // 的宽松相等天然覆盖 undefined/null 两种"键缺失"形态）。
        const key = token.value ?? '';
        const raw = currentRow?.[key];
        let bv: number;
        if (raw != null) {
          const n = Number(raw);
          bv = isNaN(n) ? 0 : n;
        } else {
          const hv = hostFieldValues?.[key];
          bv = (typeof hv === 'number' && !isNaN(hv)) ? hv : 0;
        }
        expr += bv.toString();
        break;
      }
      case 'operator': {
        const op = token.value === '\u00d7' ? '*' : token.value === '\u00f7' ? '/' : token.value!;
        expr += op;
        break;
      }
      case 'bracket_open':
        expr += '(';
        break;
      case 'bracket_close':
        expr += ')';
        break;
      case 'number':
        expr += token.value!;
        break;
      case 'component_subtotal': {
        // 优先查「组件#列名」列小计键（二阶列场景：token 引用本组件某 is_subtotal 列，
        // 而非整组件总小计）。value/tab_name 非空时才尝试列小计键，否则退回旧逻辑。
        // 旧逻辑：仅 component_code → 组件总小计（[alias(总计)] 无列名的整体引用仍走此路）。
        const _colVal = token.value || token.tab_name;
        const _colKey = token.component_code && _colVal
          ? `${token.component_code}#${_colVal}`
          : undefined;
        const _resolvedSubtotal =
          (_colKey !== undefined && componentSubtotals?.[_colKey] !== undefined
            ? componentSubtotals[_colKey]
            : undefined)
          ?? componentSubtotals?.[token.component_code!]
          ?? componentSubtotals?.[token.tab_name!]
          ?? componentSubtotals?.[token.value!]
          ?? 0;
        expr += _resolvedSubtotal.toString();
        break;
      }
      case 'previous_row_subtotal': {
        // 优先用调用方传入的上一行小计 (按 row_index 顺序累加场景);
        // 行 0 时未传入 → fallback_component_code 跨组件 subtotal 兜底.
        let v: number = 0;
        if (typeof previousRowSubtotal === 'number') {
          v = previousRowSubtotal;
        } else if (token.fallback_component_code) {
          v = componentSubtotals?.[token.fallback_component_code] ?? 0;
        }
        expr += v.toString();
        break;
      }
      case 'product_attribute':
        expr += (productAttributes?.[token.attribute_name!] ?? 0).toString();
        break;
      case 'quotation_field':
        expr += (quotationFields?.[token.value!] ?? 0).toString();
        break;
      case 'path': {
        // 优先级：driver 行级 basicDataValues > 显式 pathCache > 模块级 _globalPathCache。
        // basicDataValues 来自 useDriverExpansions 按行返回，确保多行 driver 展开时
        // 每行公式各自取自己那行的 BASIC_DATA 值（修复"所有行用第一行结果"）。
        const pathStr = token.path ?? token.value ?? '';
        let resolved: number | undefined;
        if (basicDataValues) {
          const lookup = pathStr.startsWith('{') && pathStr.endsWith('}')
            ? pathStr
            : `{${pathStr}}`;
          if (Object.prototype.hasOwnProperty.call(basicDataValues, lookup)) {
            const raw = basicDataValues[lookup];
            if (typeof raw === 'number') {
              resolved = raw;
            } else if (raw != null) {
              const first = Array.isArray(raw) ? raw[0] : raw;
              const parsed = typeof first === 'string' ? parseFloat(first)
                : typeof first === 'number' ? first
                : NaN;
              if (!isNaN(parsed)) resolved = parsed;
            }
          }
        }
        if (resolved === undefined) {
          const usedPartNo = partNo ?? _globalPartNo ?? '';
          const cache = pathCache ?? _globalPathCache;
          const key = `${usedPartNo}::${pathStr}`;
          const cached = cache?.[key];
          if (typeof cached === 'number') resolved = cached;
        }
        expr += (resolved ?? 0).toString();
        break;
      }
      case 'datasource_field': {
        // K1: 引用同行 DATA_SOURCE 字段的解析结果. token.name 字段名, 求值期 fieldValues[name]
        // 应已含 DATA_SOURCE 解析后的值 (computeAllFormulas 前置写入).
        const dsName = token.name ?? token.value ?? '';
        expr += (fieldValues[dsName] ?? 0).toString();
        break;
      }
      case 'cross_tab_ref': {
        // ── 公共 key 比较器 (blank/number-safe) ──
        const keyEq = (av: any, bv: any): boolean => {
          const blank = (x: any) => x == null || String(x).trim() === '';
          if (blank(av) || blank(bv)) return false;
          const na = Number(av), nb = Number(bv);
          if (!isNaN(na) && !isNaN(nb)) return na === nb;
          return String(av).trim() === String(bv).trim();
        };

        // ── aggregateRows: 纯重构抽取，存量 NONE/SUM/AVG/COUNT/MAX/MIN 行为逐字等价 ──
        //
        // projectToHostKey 参数:
        //   false → 外层 source join（存量路径，NONE/SUM/... 保持旧行为，空集→0）
        //   true  → KSUM 子 token，决策 K 空集分流:
        //            KSUM/KCOUNT    空集 → 0 (I-1 静默)
        //            KAVG/KMAX/KMIN 空集 → null (I-2 → 整外层塌 0 + outDiag)
        const aggregateRows = (
          rows: Array<Record<string, any>>,
          matchPairs: Array<{ a: string; b: string }>,
          hostRow: Record<string, any> | undefined,
          targetExpr: ExpressionToken[] | undefined,
          agg: string,
          target: string | undefined,
          isProjectToHostKey: boolean,
        ): { value: number | null; multiMatchErr: boolean } => {
          const hits = rows.filter((ar) =>
            matchPairs.every((p) => keyEq(ar[p.a], hostRow?.[p.b]))
            && evalPredicate(token.predicate, ar, hostRow ?? {})
          );
          const A = agg.toUpperCase();
          const toNum = (v: any) => { const n = Number(v); return isNaN(n) ? null : n; };
          const hasTE = !!(targetExpr && targetExpr.length > 0);

          // §4.3 多 source 广播: 粗 source >1 命中时的错误标志 (闭包)
          // evalRowExpr 命中 >1 时置 true → aggregateRows 合并进 multiMatchErr
          let multiSrcHitErr = false;

          // evalRowExpr: 对单驱动行 ar 求值 targetExpr，透传所有求值上下文。
          // currentRow 传 ar 使 KSUM 子 token(projectToHostKey) 按驱动行键塌缩；
          // b_field 取 ar[field] 等价于原宿主行（因 match key 字段值对齐）。
          const evalRowExpr = (ar: Record<string, any>): number => {
            const aFieldValues: Record<string, number> = {};

            // §4.3 多 source 广播（仅当 token.sources 存在且含 ≥2 项时触发）:
            // N=1 / 无 sources / 纯 KSUM 容器路径 → 跳过，aFieldValues 完全同既有逻辑。
            if (token.sources && token.sources.length >= 2) {
              // 步骤1: 先放更粗 source 的列（低优先级）
              for (const s of token.sources.slice(1)) {
                const coarseRows = crossTabRows?.[s.source] ?? [];
                // 用 s.match 命中: cr[p.a] 对应粗 source 列, ar[p.b] 对应驱动行（公共行键）
                const hitRows = coarseRows.filter((cr) =>
                  s.match.every((p) => keyEq(cr[p.a], ar[p.b]))
                );
                if (hitRows.length === 0) {
                  // 0 命中 → 该粗 source 的列不注入（其 field 取值缺省 → 项=0）
                  continue;
                }
                if (hitRows.length > 1) {
                  // >1 命中 → 粗 source 行键非唯一 → 标记错误（等价 null → multiMatchErr）
                  multiSrcHitErr = true;
                  continue;
                }
                // 恰好 1 命中 → 把该行所有数值列并入 aFieldValues（低优先级，后续被驱动行覆盖）
                for (const k of Object.keys(hitRows[0])) {
                  const n = Number(hitRows[0][k]);
                  if (!isNaN(n)) aFieldValues[k] = n;
                }
              }
              // 步骤2: 放驱动行 ar 的列（高优先级，覆盖粗 source 同名列）
              for (const k of Object.keys(ar)) { const n = Number(ar[k]); if (!isNaN(n)) aFieldValues[k] = n; }
            } else {
              // N=1 退化路径（无 sources / 纯 KSUM 容器）: 逐字保留旧逻辑，零变化
              for (const k of Object.keys(ar)) { const n = Number(ar[k]); if (!isNaN(n)) aFieldValues[k] = n; }
            }

            // §4.3: 合并驱动行(ar)与宿主行(hostRow)作为递归的 currentRow。
            // - b_field 取宿主列（如"数量"）→ 保留原 hostRow 字段（N=1 退化路径零变化）
            // - KSUM 子 token 按 ar 的行键（如"料件"）做 match → ar 字段覆盖同名项（match key 对齐）
            const mergedRow = hostRow ? { ...hostRow, ...ar } : ar;
            return evaluateExpression(
              targetExpr!, aFieldValues, componentSubtotals, productAttributes, quotationFields,
              pathCache, partNo, basicDataValues, undefined, globalVariableDefs,
              mergedRow,    // 合并行：b_field 走宿主字段，KSUM 内层 match 走 ar 字段
              crossTabRows,
              outDiag,      // 透传 diag 袋：内层 KAVG/KMAX/KMIN 空集写 crossTabError 穿透到最外层
              treeCtx,      // 防御性透传（cross_tab_ref.targetExpr 的白名单本不含 tree_attr，但透传无害）
              // repair-0803：宿主已算字段值原样透传（不并入 aFieldValues —— 那里装的是源页签行的列，
              // 混入会让 targetExpr 内的 b_field 串到源页签列上）。供 b_field 键缺失时回落。
              // 与后端 `sub.hostFieldValues = ctx.hostFieldValues;`（targetRowValue :618）逐字对齐。
              hostFieldValues,
            );
          };

          if (A === 'COUNT') return { value: hits.length, multiMatchErr: false };

          if (A === 'NONE') {
            // ① NONE 旁路：保留原始"零变化"行为
            if (hits.length === 0) return { value: 0, multiMatchErr: false };
            if (hits.length > 1) return { value: 0, multiMatchErr: true };   // ERR 旁路
            const v = hasTE ? evalRowExpr(hits[0]) : (toNum(hits[0][target ?? '']) ?? 0);
            return { value: v, multiMatchErr: false };
          }

          // 【I-1/I-2 决策 K 空集分流】—— 在统一"空集→0 提前返回"之前
          if (hits.length === 0) {
            if (isProjectToHostKey && (A === 'AVG' || A === 'MAX' || A === 'MIN')) {
              // I-2: KAVG/KMAX/KMIN 空集 → null → 整外层塌 0 + outDiag（由调用侧写入）
              return { value: null, multiMatchErr: false };
            }
            // I-1: KSUM/KCOUNT 空集 → 0 (静默); 外层 SUM/AVG/... 空集 → 0 (旧行为不变)
            return { value: 0, multiMatchErr: false };
          }

          const nums = hasTE ? hits.map(evalRowExpr) : hits.map((h) => toNum(h[target ?? '']));
          // §4.3: 粗 source >1 命中时 multiSrcHitErr=true → 等价 multiMatchErr（整项塌 0 + crossTabError）
          if (multiSrcHitErr || nums.some((n) => n === null)) return { value: null, multiMatchErr: true };
          const arr = nums as number[];
          const v = A === 'SUM' ? arr.reduce((s, x) => s + x, 0)
                  : A === 'AVG' ? arr.reduce((s, x) => s + x, 0) / arr.length
                  : A === 'MAX' ? Math.max(...arr)
                  : A === 'MIN' ? Math.min(...arr) : 0;
          return { value: v, multiMatchErr: false };
        };

        const agg = (token.agg ?? 'NONE').toUpperCase();
        const hasTE = !!(token.targetExpr && token.targetExpr.length > 0);

        if (token.projectToHostKey) {
          // ── KSUM 子 token 分支：按宿主行(currentRow)塌缩成标量 ──
          const rows = crossTabRows?.[token.source ?? ''] ?? [];
          const r = aggregateRows(rows, token.match ?? [], currentRow, token.targetExpr, agg, token.target, true);
          if (r.value === null) {
            // I-2: KAVG/KMAX/KMIN 空集 → 注入非法表达式 → 外层 try/catch → 0; 同时写 outDiag
            // C-2: 区分"空集无定义"与"含非数值字段/多命中无法聚合"两种 null 原因
            if (outDiag) {
              outDiag.crossTabError = r.multiMatchErr
                ? `[${token.sourceLabel ?? token.source}] ${token.agg} 含非数值字段,无法聚合`
                : `[${token.sourceLabel ?? token.source}] ${token.agg} 命中 0 行,无定义`;
            }
            expr += '(null.x)';
          } else {
            expr += r.value.toString();
          }
          break;
        }

        // ── 外层 source join 分支（存量路径，N=1 无嵌套退化路径零变化）──
        // §4.3: 驱动 = sources[0].source（与 token.source 镜像等价，显式引用更清晰）
        const driverSource = (token.sources && token.sources.length >= 2)
          ? token.sources[0].source
          : (token.source ?? '');
        const rows = crossTabRows?.[driverSource] ?? [];
        const r = aggregateRows(rows, token.match ?? [], currentRow, token.targetExpr, agg, token.target, false);
        let crossTabError = r.multiMatchErr;
        // 错误路径: 注入非法表达式让外层 try/catch 捕获并返回 0 (对齐后端 error→0 行为)
        // ★ 旁路(数值零改): 同时把可读原因写入 outDiag,供渲染层显示 ⚠ 错误态。
        if (crossTabError && outDiag) {
          const src = token.source ?? '';
          const tgt = token.target ?? (hasTE ? '表达式' : '');
          const ref = src || tgt ? `[${src}${tgt ? '.' + tgt : ''}] ` : '';
          outDiag.crossTabError = `${ref}细项引用命中多行,请改用 SUM 等聚合(或引用「(总计)」)`;
        }
        expr += crossTabError ? '(null.x)' : (r.value ?? 0).toString();
        break;
      }
      case 'global_variable': {
        // V104: 编译期已经写入 token.path (前端解析全局变量时即用 globalVarService 拼路径),
        // 求值期沿 path 流水线: basicDataValues → pathCache → 0.
        // 静态 key 直接固化到 path; 动态 key (key_field_refs) 运行时按当前行字段重写 path.

        // ★ AP-49 方向 A：优先查 @gvar:CODE (与后端 ComponentDriverService.basicDataValues 契约对齐)
        // 后端 batchExpand 对 GLOBAL_VARIABLE 类型 driver 注入 basicDataValues['@gvar:CODE'] = 当前行对应值
        // 这一路径比 BNF path 动态重写更直接，避免 key_field_refs 运行时 lookup 失败导致单价=0
        {
          const gvCode = (token as any).code ?? (token as any).value ?? '';
          const gvKey = `@gvar:${gvCode}`;
          if (gvCode && basicDataValues && Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
            const gvRaw = basicDataValues[gvKey];
            if (gvRaw != null && !(Array.isArray(gvRaw) && gvRaw.length === 0)) {
              const gvNum = Number(Array.isArray(gvRaw) ? gvRaw[0] : gvRaw);
              if (!isNaN(gvNum)) {
                expr += String(gvNum);
                break;
              }
            }
          }
        }

        let pathStr = token.path ?? '';

        // 动态 key 运行时重写 path (key_field_refs 存在且 token.path 为空时触发)
        if (!pathStr && token.key_field_refs && globalVariableDefs && currentRow) {
          const code = token.code;
          const def = code ? globalVariableDefs[code] : undefined;
          if (def) {
            pathStr = compileGlobalVariableTokenForRow(token, def, currentRow);
          } else {
            // 诊断: gvDefs 不含 code (race condition 或 list 失败)
            if (typeof window !== 'undefined' && (window as any).__GV_DEBUG__) {
              console.warn('[gv-debug] def miss', { code, defsKeys: Object.keys(globalVariableDefs || {}) });
            }
          }
        }

        if (!pathStr) {
          // 诊断: 重写失败 — 可能 currentRow 缺字段 or token 缺 code
          if (typeof window !== 'undefined' && (window as any).__GV_DEBUG__ && token.key_field_refs) {
            console.warn('[gv-debug] pathStr empty', {
              token_code: token.code,
              token_key_field_refs: token.key_field_refs,
              currentRow_keys: currentRow ? Object.keys(currentRow) : 'undefined',
              hasGvDefs: !!globalVariableDefs,
            });
          }
          expr += '0'; break;
        }
        let resolved: number | undefined;
        if (basicDataValues) {
          const lookup = pathStr.startsWith('{') && pathStr.endsWith('}') ? pathStr : `{${pathStr}}`;
          if (Object.prototype.hasOwnProperty.call(basicDataValues, lookup)) {
            const raw = basicDataValues[lookup];
            const first = Array.isArray(raw) ? raw[0] : raw;
            const parsed = typeof first === 'number' ? first
              : typeof first === 'string' ? parseFloat(first) : NaN;
            if (!isNaN(parsed)) resolved = parsed;
          }
        }
        if (resolved === undefined) {
          const usedPartNo = partNo ?? _globalPartNo ?? '';
          const cache = pathCache ?? _globalPathCache;
          const cacheKey = `${usedPartNo}::${pathStr}`;
          const cached = cache?.[cacheKey];
          if (typeof cached === 'number') {
            resolved = cached;
          } else {
            // 诊断: cache miss — 检查 key 格式是否正确、cache 是否已预热
            if (typeof window !== 'undefined' && (window as any).__GV_DEBUG__) {
              console.warn('[gv-debug] cache miss', {
                lookupKey: cacheKey,
                cacheSize: Object.keys(cache || {}).length,
                hasExactKey: cache ? cacheKey in cache : false,
                sampleKeys: cache ? Object.keys(cache).filter(k => k.includes('COST_ELEMENT')).slice(0, 5) : [],
              });
            }
          }
        }
        expr += (resolved ?? 0).toString();
        break;
      }
      // task-0803：BOM 页签父子取值。求值结果当字面量追加（无树上下文 / 越界等一律 0，见 evalTreeRefToken/evalTreeAttrToken）。
      case 'tree_ref': {
        expr += evalTreeRefToken(token, treeCtx).toString();
        break;
      }
      case 'tree_attr': {
        expr += evalTreeAttrToken(token, treeCtx).toString();
        break;
      }
    }
  }

  // task-0801：十进制精确求值，替代 new Function（不再 eval 任意 JS，退化为纯算术解析器，
  // 更安全）。不再 toDecimalPlaces(4) 中途截断 —— 返回值可能带较长小数，这是预期行为
  // （中间不截断），由显示层 formatNumber（兜底 DISPLAY_SCALE=6）与 payload 规范化在边界处理。
  // 本求值点对外承诺"始终返回 number"（不返回 null）：非法表达式沿用旧 catch→0 兜底语义。
  //
  // task-0729 B9 nz-001/nz-002（除零/0÷0 归零）merge master 时的处置（2026-08-03）：原来这里
  // 有一段独立的 !Number.isFinite(raw) 补丁，用于把 Infinity/NaN 归零。master 侧
  // evaluateArithmetic()（precision.ts）已经在更底层解决了同一问题——ArithDecimalParser.term()
  // 除法分支对 r.isZero() 显式短路返回 Decimal(0)（根本不会产生 Infinity/NaN），出口还有
  // 第二道防线 `return v.isFinite() ? v : new Decimal(0)`。已用隔离脚本实测
  // 5/0→0、0/0→0，两道防线完全覆盖原补丁的语义，故本次合并整体采用 master 一侧，原补丁作废
  // （见 merge-master-前端冲突预案.md §1 + formula-golden nz-001/nz-002 用例复测）。
  const result = evaluateArithmetic(expr);
  return result === null ? 0 : result.toNumber();
}

export function isWithinTolerance(frontendValue: number, backendValue: number, tolerance = 0.01): boolean {
  return Math.abs(frontendValue - backendValue) <= tolerance;
}

/**
 * LIST_FORMULA 字段的字符串公式求值.
 *
 * <p>支持的 token 形态 (字符串内嵌, 不走 token 数组):
 * <ul>
 *   <li>`[字段名]` — 当前行其他字段值 (从 rowFieldValues 取)</li>
 *   <li>`[表名.列名]` — 列表项原生列值 (从 listItemColumns 取, 表名前缀必须等于 source_table)</li>
 *   <li>`{GV_CODE}` — 全局变量 (无 `.` 时, 从 globalVarValues 取)</li>
 *   <li><b>`{表名.列名}` / `{表名[谓词].列名}` — BNF 数据库路径 (含 `.` 时, 从 basicDataValues 取, 2026-05-20 新增)</b></li>
 *   <li>数字 / 运算符 + - * / ( ) . — 标准算术</li>
 * </ul>
 *
 * <p>典型公式:
 * <ul>
 *   <li>`[基础工时] * 1.2` — 引用本行字段</li>
 *   <li>`[v_process_list.unit_price] * 1.5` — 引用列表项列</li>
 *   <li>`{PROCESS_DEFAULT_PRICE}` — 引用全局变量</li>
 *   <li>`{mat_bom.composition_pct} * 5` — 引用 BNF 数据库路径 (2026-05-20)</li>
 *   <li>`{mat_part.unit_weight} / 1000 * [单价]` — 混合 BNF path + 本行字段</li>
 * </ul>
 *
 * @param formula 公式字符串 (空字符串 → 返 null)
 * @param rowFieldValues 当前行其他字段 name → 值
 * @param listItemColumns 当前行对应的列表项 col → 值 (key 不带前缀, 求值时按 token 拆 "<table>.<col>" → col)
 * @param sourceTable 列表项绑定的源表名 (token 前缀必须匹配, 否则视为本行字段)
 * @param globalVarValues 全局变量 code → 值
 * @param basicDataValues 2026-05-20 新增 — driver expand 返的 `{path}` → 值 (来自 batch-expand basicDataValues, key 含花括号 `{table.col}`)
 * @returns 求值结果数值. 公式空/解析失败 → null
 */
export function evaluateListFormulaString(
  formula: string | undefined,
  rowFieldValues: Record<string, any>,
  listItemColumns: Record<string, any>,
  sourceTable: string,
  globalVarValues?: Record<string, number>,
  basicDataValues?: Record<string, any>,
  // 2026-05-20: LIST_FORMULA BNF path fallback partNo.
  // 含条件谓词的 BNF path (如 mat_bom[element_name='Sn'].net_qty) 在 driver expand 上下文
  // 被 ImplicitJoinRewriter 注入 seq_no 等驱动列谓词后可能查空 (mat_bom 中 Sn 行 seq_no=2
  // 与 driver mat_process 行 seq_no=1 不匹配). 缺值时回退到 globalPathCache (partNo 维度,
  // 不带 driver 行注入) — 与 batch-evaluate 行为对齐.
  partNo?: string,
  // 2026-05-20: React state cache (来自 usePathFormulaCache 返值), 优先用于 fallback
  // 避免模块级 _globalPathCache 在 hook 还没 setGlobalPathCache 之前为空导致首渲染失败.
  pathCacheState?: Record<string, any>,
): number | null {
  if (!formula || !formula.trim()) return null;
  let expr = formula.trim();

  // 1. {...} 区分 BNF path vs 全局变量 (2026-05-20):
  //    - 含 `.` → BNF 数据库路径, 从 basicDataValues 取 (key = "{path}" 带花括号)
  //    - 不含 `.` → 全局变量 code, 从 globalVarValues 取
  expr = expr.replace(/\{([^}]+)\}/g, (_, code) => {
    const trimmed = (code as string).trim();
    if (trimmed.includes('.')) {
      // BNF path — basicDataValues 的 key 形态是 "{table.col}" 含花括号
      const lookupKey = '{' + trimmed + '}';
      let v = basicDataValues?.[lookupKey];
      const fromBdv = v;
      // 缺值 fallback: partNo 维度的 globalPathCache (driver-free 解析)
      let partKey = '';
      let fromCache: any = undefined;
      if (v == null && partNo) {
        partKey = `${partNo}::${trimmed}`;
        // 优先 React state cache (调用方传入), 再退到模块级
        const cache = pathCacheState ?? (getGlobalPathCache() as Record<string, any>);
        fromCache = cache?.[partKey];
        v = fromCache;
      }
      if (v == null) return '0';
      return numericToStr(v);
    }
    // 全局变量 (老行为)
    const v = globalVarValues?.[trimmed];
    return typeof v === 'number' ? String(v) : '0';
  });

  // 2. [token] → 数字字面量
  //   - token 含 '.': 当作 [表名.列名]; 前缀=sourceTable 时从 listItemColumns 取 col
  //   - token 不含 '.': 当作 [字段名] 从 rowFieldValues 取
  expr = expr.replace(/\[([^\]]+)\]/g, (_, tokenStr) => {
    const t = (tokenStr as string).trim();
    if (t.includes('.')) {
      const dotIdx = t.indexOf('.');
      const prefix = t.substring(0, dotIdx).trim();
      const col = t.substring(dotIdx + 1).trim();
      // 前缀匹配源表 → 从列表项取
      if (prefix === sourceTable) {
        const v = listItemColumns[col];
        return numericToStr(v);
      }
      // 其他前缀 (将来扩展跨表): 暂当 0
      return '0';
    }
    const v = rowFieldValues[t];
    return numericToStr(v);
  });

  // 3. 仅允许数字/运算符/括号/小数点/空格（安全防线，task-0801 不改）
  if (!/^[\d+\-*/().\s]*$/.test(expr)) {
    return null;
  }

  // task-0801：十进制精确求值，替代 new Function。不再 toDecimalPlaces(4) 中途截断
  // （中间不截断，由显示层/payload 规范化在边界处理）。非法表达式沿用既有约定返回 null。
  const result = evaluateArithmetic(expr);
  return result === null ? null : result.toNumber();
}

function numericToStr(v: any): string {
  if (v == null || v === '') return '0';
  if (typeof v === 'number') return String(v);
  if (typeof v === 'boolean') return v ? '1' : '0';
  const n = parseFloat(String(v));
  return isNaN(n) ? '0' : String(n);
}
