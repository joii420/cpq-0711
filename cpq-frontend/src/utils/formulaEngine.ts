import Decimal from 'decimal.js';

export interface ExpressionToken {
  type: 'field' | 'operator' | 'bracket_open' | 'bracket_close' | 'number' | 'component_subtotal' | 'product_attribute' | 'quotation_field' | 'path' | 'global_variable';
  value?: string;
  label?: string;
  component_code?: string;
  tab_name?: string;
  attribute_name?: string;
  /** path token 专用:BNF 路径表达式(如 mat_part.unit_weight 或 元素BOM[元素='Ag'].组成含量) */
  path?: string;
  /** global_variable 专用:注册表 code */
  code?: string;
  /** global_variable 专用:静态 key (列名→字面值) */
  key_values?: Record<string, any>;
  /** global_variable 专用:动态 key (列名→同行字段名, 求值时取行数据) */
  key_field_refs?: Record<string, string>;
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
): number {
  // Build expression string from tokens
  let expr = '';
  for (const token of tokens) {
    switch (token.type) {
      case 'field':
        expr += (fieldValues[token.value!] ?? 0).toString();
        break;
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
      case 'component_subtotal':
        expr += (componentSubtotals?.[token.component_code!]
          ?? componentSubtotals?.[token.tab_name!]
          ?? componentSubtotals?.[token.value!]
          ?? 0).toString();
        break;
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
      case 'global_variable': {
        // V104: 编译期已经写入 token.path (前端解析全局变量时即用 globalVarService 拼路径),
        // 求值期沿 path 流水线: basicDataValues → pathCache → 0.
        // 静态 key 直接固化到 path; 动态 key (key_field_refs) 在编辑器侧根据当前行字段重写 path。
        const pathStr = token.path ?? '';
        if (!pathStr) { expr += '0'; break; }
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
          const cached = cache?.[`${usedPartNo}::${pathStr}`];
          if (typeof cached === 'number') resolved = cached;
        }
        expr += (resolved ?? 0).toString();
        break;
      }
    }
  }

  try {
    // Use Function constructor instead of eval for slightly better practice
    // Safe: we built the expression ourselves from controlled tokens
    const fn = new Function(`return (${expr})`);
    const result = new Decimal(fn());
    return result.toDecimalPlaces(4).toNumber();
  } catch {
    return 0;
  }
}

export function isWithinTolerance(frontendValue: number, backendValue: number, tolerance = 0.01): boolean {
  return Math.abs(frontendValue - backendValue) <= tolerance;
}
