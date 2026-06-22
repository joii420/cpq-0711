import { bnfDriverLookupKey } from './useDriverExpansions';
import { formatPathValue } from './components/formatPathValue';
import type { ComponentField } from './QuotationStep2';

export interface InputDefaultCtx {
  basicDataValues?: Record<string, any>;
  partNo?: string;
  pathCache?: Record<string, any>;
}

/** 与 ComponentCell.onChange 的 /^-?\d*\.?\d*$/ 同源：合法返回 number，否则 undefined。 */
export function coerceInputNumber(v: unknown): number | undefined {
  if (typeof v === 'number') return isNaN(v) ? undefined : v;
  if (typeof v !== 'string') return undefined;
  if (v === '' || !/^-?\d*\.?\d*$/.test(v)) return undefined;
  const n = parseFloat(v);
  return isNaN(n) ? undefined : n;
}

/**
 * 仅解析 default_source（GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA），**不回退静态 content**。
 * 源未命中（或字段非 INPUT*）返回 undefined。供"快照回填(bake)"等只应冻结真实源值的场景使用——
 * content 兜底归 snapshotRows(无源) + 实时渲染，不该被 bake 冻结锁死。
 */
export function resolveInputDefaultSourceOnly(field: ComponentField, ctx: InputDefaultCtx): string | number | undefined {
  const ft = field.field_type;
  if (ft !== 'INPUT_TEXT' && ft !== 'INPUT_NUMBER' && ft !== 'INPUT') return undefined;

  const bdv = ctx.basicDataValues;
  const ds = field.default_source as { type?: string; code?: string; path?: string } | undefined | null;
  let resolved: any = undefined;

  if (ds && bdv) {
    if (ds.type === 'GLOBAL_VARIABLE' && ds.code) {
      const gvKey = `@gvar:${ds.code}`;
      if (Object.prototype.hasOwnProperty.call(bdv, gvKey)) {
        const v = bdv[gvKey];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
    } else if ((ds.type === 'BNF_PATH' || ds.type === 'BASIC_DATA') && ds.path) {
      const lk = bnfDriverLookupKey(ds.path);
      if (Object.prototype.hasOwnProperty.call(bdv, lk)) {
        const v = bdv[lk];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
      // BNF_PATH 才走 pathCache 兜底；BASIC_DATA 只吃行级(单列 ASCII 会失败)
      if (resolved === undefined && ds.type === 'BNF_PATH' && ctx.partNo && ctx.pathCache) {
        const v = ctx.pathCache[`${ctx.partNo}::${ds.path}`];
        if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
      }
    }
  }

  if (resolved != null) {
    if (typeof resolved === 'number') return resolved;
    const fmt = formatPathValue(resolved);
    if (fmt != null) return fmt;
  }
  return undefined;
}

/**
 * 导入带出"一次性烘焙(bake)"取值：把默认值固化进行数据，使之后清空可保持空白。
 * - 字段含 default_source：只烘"已解析到的源值"(同 resolveInputDefaultSourceOnly)；源未命中返回
 *   undefined → 不提前冻结 content，等驱动补值后下一轮再烘（避免静态 content 被 bakedRef 锁死）。
 * - 字段无 default_source：直接烘静态 content。
 * 非 INPUT* / 无可烘值 → undefined。
 */
export function resolveInputDefaultForBake(field: ComponentField, ctx: InputDefaultCtx): string | number | undefined {
  const ft = field.field_type;
  if (ft !== 'INPUT_TEXT' && ft !== 'INPUT_NUMBER' && ft !== 'INPUT') return undefined;
  if (field.default_source) return resolveInputDefaultSourceOnly(field, ctx);
  if (field.content != null && field.content !== '') return field.content;
  return undefined;
}

/**
 * 解析 INPUT_TEXT / INPUT_NUMBER 的有效默认值（不判 row[key]——调用方先判已有值）。
 * 优先级：default_source(GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA，实时) > 静态 content > undefined。
 */
export function resolveInputDefault(field: ComponentField, ctx: InputDefaultCtx): string | number | undefined {
  const fromSource = resolveInputDefaultSourceOnly(field, ctx);
  if (fromSource !== undefined) return fromSource;

  const ft = field.field_type;
  if (ft !== 'INPUT_TEXT' && ft !== 'INPUT_NUMBER' && ft !== 'INPUT') return undefined;
  if (field.content != null && field.content !== '') return field.content;
  return undefined;
}
