import { bnfDriverLookupKey } from './useDriverExpansions';
import { formatPathValue } from './components/formatPathValue';
import type { ComponentField } from './QuotationStep2';
import { isDecimalString, type DecimalString } from '../../utils/precision';

export interface InputDefaultCtx {
  basicDataValues?: Record<string, any>;
  partNo?: string;
  pathCache?: Record<string, any>;
}

/** Validate decimal text without changing its spelling (for example, keep `1.2300`). */
export function coerceInputNumber(v: unknown): DecimalString | undefined {
  return isDecimalString(v) ? v : undefined;
}

/**
 * 仅解析 default_source（GLOBAL_VARIABLE | BNF_PATH | BASIC_DATA），**不回退静态 content**。
 * 源未命中（或字段非 INPUT*）返回 undefined。供"快照回填(bake)"等只应冻结真实源值的场景使用——
 * content 兜底归 snapshotRows(无源) + 实时渲染，不该被 bake 冻结锁死。
 */
export function resolveInputDefaultSourceOnly(field: ComponentField, ctx: InputDefaultCtx): string | undefined {
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
    // 🔑 裸 JS number 的处理口径（repair-260911 F-1 / AC-R3 · AC-R5，**改这里前先读完这段**）
    //
    // task-0810「前后端统一十进制精度契约」把本行从 `return resolved` 改成了 `return undefined`：
    // IEEE-754 double 装不下 numeric(26,12)，任何经过 JS number 的**小数**都可能已经丢过精度，
    // 此时无论怎么格式化都是在给一个已经错了的值化妆 —— 所以小数必须丢弃，这条不变。
    //
    // 但「整数」不在这个风险里：|n| <= Number.MAX_SAFE_INTEGER 的整数在 double 里是**精确**的，
    // String(n) 与源 JSON 字面量逐字符相同，不存在"已经丢过精度"的可能。
    // 同一口径在 losslessJson.ts 的 STRUCTURAL_INTEGER_KEYS 里早已确立（那里也是
    // `Number.isSafeInteger` 才放行 number），且名单里就有 '项次' / '_项次' / '序号'。
    //
    // ⇒ 放行安全整数**不是推翻精度契约，而是把已有的先例补到本函数上**。
    //    为什么本函数漏网：快照通道走 lossless 解析，'项次' 命中名单；而实时 batch-expand 走
    //    axios 默认 JSON.parse（无 lossless），且 key 形如 `{$builder_..._物料BOM_项次}` 也不匹配
    //    名单 → 到这里是裸 number `1` → 被整条丢掉 → BOM 页签「项次」列全空（问题 4 的根因）。
    //
    // 🚫 转成字符串后仍必须走下面原有的 formatPathValue / coerceInputNumber 链路，不要绕过：
    //    下游一律按 DecimalString 消费，绕过去会把 number 漏进精度链路。
    // 🚫 非安全整数（含 2^53 以上）、小数、NaN / Infinity 一律仍然 return undefined。
    if (typeof resolved === 'number') {
      if (!Number.isSafeInteger(resolved)) return undefined;
      resolved = String(resolved);
    }
    const fmt = formatPathValue(resolved);
    if (fmt != null) return ft === 'INPUT_NUMBER' ? coerceInputNumber(fmt) : fmt;
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
export function resolveInputDefaultForBake(field: ComponentField, ctx: InputDefaultCtx): string | undefined {
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
export function resolveInputDefault(field: ComponentField, ctx: InputDefaultCtx): string | undefined {
  const fromSource = resolveInputDefaultSourceOnly(field, ctx);
  if (fromSource !== undefined) return fromSource;

  const ft = field.field_type;
  if (ft !== 'INPUT_TEXT' && ft !== 'INPUT_NUMBER' && ft !== 'INPUT') return undefined;
  if (field.content != null && field.content !== '') return field.content;
  return undefined;
}
