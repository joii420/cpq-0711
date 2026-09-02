/**
 * 含量配置的共享判据（task-260901）。
 *
 * 🚨 **同一条规则只能有一份实现**：M-0a 明确「UI 新建材质」与「导入」是同一条规则的两个入口，
 * 判据、错误文案、「不一致就整体拒绝」的处置必须一致。本模块是前端侧那一份，
 * 被 F-5（含量配置抽屉）/ F-13（新建材质配方卡片）/ F-9（选配自定义含量）共用。
 *
 * 🚨 **含量一律按字符串处理**：`default_pct` 是 `numeric(16,12)`，
 * 禁止 `Number(pct)` / `parseFloat` —— 会丢尾数且无法区分 `12.345678901200` 与 `12.3456789012`。
 * 一切数值比较走 `decimal.js`（`utils/precision.ts`）。
 */
import Decimal from 'decimal.js';
import {
  isDecimalString,
  sumDecimal,
  toDecimal,
  trimTrailingZeros,
  type DecimalString,
} from '../../utils/precision';

/**
 * 合计容差 —— 100 制的 2，等于 0~1 制的 0.02（`api.md` §2.2 `CONFIG_SUM_NOT_ONE`）。
 * ⚠️ 只用于「合计是不是 1」，**不用于**「两个配置是不是同一个」（M-4）。
 */
export const SUM_TOLERANCE_PCT = '2';

/** 单值上限（100 制） */
export const MAX_PCT = '100';

export interface PctRow {
  elementNo: string;
  elementCode: string;
  /** 用户输入的原始字符串（去尾随零形态），空串 = 未填 */
  pct: DecimalString;
}

/** 单个含量是否合法：必须是纯十进制串、> 0 且 ≤ 100（`CONFIG_PCT_ILLEGAL`） */
export function isPctLegal(pct: string | null | undefined): boolean {
  if (!isDecimalString(pct ?? '')) return false;
  const d = toDecimal(pct ?? '');
  return d.greaterThan('0') && d.lessThanOrEqualTo(MAX_PCT);
}

/** `含量必须大于 0 且不超过 100：{elementCode}` —— 与 `api.md` 的 `CONFIG_PCT_ILLEGAL` 同文案 */
export function pctIllegalText(elementCode?: string): string {
  return elementCode
    ? `含量必须大于 0 且不超过 100：${elementCode}`
    : '含量必须大于 0 且不超过 100';
}

export function sumPct(rows: ReadonlyArray<{ pct: DecimalString }>): Decimal {
  return sumDecimal(rows.map((r) => r.pct));
}

/** 合计是否在容差内（|Σ − 100| ≤ 2） */
export function isSumOk(sum: Decimal): boolean {
  return sum.minus('100').abs().lessThanOrEqualTo(SUM_TOLERANCE_PCT);
}

/** 合计的显示文本（100 制，去尾随零），如 `100` / `85` / `12.3456789012` */
export function sumDisplayPct(sum: Decimal): string {
  return trimTrailingZeros(sum.toFixed());
}

/** `含量合计必须为 1，实际 1.08` —— 与 `api.md` 的 `CONFIG_SUM_NOT_ONE` 同文案（0~1 制、2 位） */
export function sumNotOneText(sum: Decimal): string {
  return `含量合计必须为 1，实际 ${sum.dividedBy('100').toFixed(2)}`;
}

/** `应为 100%，还差 15%` / `应为 100%，超出 8%` —— 原型 3 / 原型 6 的行内提示 */
export function sumGapText(sum: Decimal): string {
  const gap = new Decimal('100').minus(sum);
  if (gap.isZero()) return '应为 100%';
  return gap.greaterThan(0)
    ? `应为 100%，还差 ${trimTrailingZeros(gap.toFixed())}%`
    : `应为 100%，超出 ${trimTrailingZeros(gap.negated().toFixed())}%`;
}

/** 元素种类集合（按 elementNo），用于 M-0a / M-5b 的集合相等判定 */
export function elementNoSet(rows: ReadonlyArray<{ elementNo: string }>): Set<string> {
  return new Set(rows.map((r) => r.elementNo).filter(Boolean));
}

export function setsEqual(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a.size !== b.size) return false;
  for (const v of a) if (!b.has(v)) return false;
  return true;
}

/** 集合的展示串，如 `{Ag, Ni}` —— 错误文案要指名道姓 */
export function formatElementSet(rows: ReadonlyArray<{ elementNo: string; elementCode: string }>): string {
  const seen = new Set<string>();
  const codes: string[] = [];
  rows.forEach((r) => {
    if (!r.elementNo || seen.has(r.elementNo)) return;
    seen.add(r.elementNo);
    codes.push(r.elementCode || r.elementNo);
  });
  return `{${codes.join(', ')}}`;
}

/**
 * M-4 配置相等判据 = **元素集合相同 且 每个元素含量 `compareTo == 0`**（值相等，忽略 scale 差异）。
 * 🚫 不套用 Σ 的容差 —— 容差只用于「Σ 是不是 1」。
 * ⇒ `90` 与 `90.000000000000` 算同一个值。
 */
export function configsValueEqual(
  a: ReadonlyArray<{ elementNo: string; pct: DecimalString }>,
  b: ReadonlyArray<{ elementNo: string; pct: DecimalString }>,
): boolean {
  if (!setsEqual(elementNoSet(a), elementNoSet(b))) return false;
  const mapB = new Map(b.map((r) => [r.elementNo, r.pct]));
  return a.every((r) => {
    const other = mapB.get(r.elementNo);
    if (other === undefined) return false;
    return toDecimal(r.pct).comparedTo(toDecimal(other)) === 0;
  });
}

/**
 * `配方1 与 配方2 的元素种类不同（配方1={Ag, Ni}，配方2={Ag, Cu}）。同一材质下各配方必须使用相同的元素`
 * —— 与后端 `COMPOSITION_INCONSISTENT_ACROSS_CONFIGS` / 导入侧
 * `同一材质内各组元素组成不一致(...)` 是同一判据（M-0a / M-5b）。
 */
export function compositionInconsistentText(
  i: number,
  j: number,
  setI: string,
  setJ: string,
): string {
  return `配方${i} 与 配方${j} 的元素种类不同（配方${i}=${setI}，配方${j}=${setJ}）。同一材质下各配方必须使用相同的元素`;
}

/** `配方1 与 配方2 的含量完全相同，请删除其中一组` —— 后端 `CONFIG_DUPLICATED_IN_REQUEST` */
export function configDuplicatedInRequestText(i: number, j: number): string {
  return `配方${i} 与 配方${j} 的含量完全相同，请删除其中一组`;
}

/** `该含量配比与已有配置 00006-01 完全相同` —— 后端 `CONFIG_DUPLICATED` */
export function configDuplicatedText(configNo: string): string {
  return `该含量配比与已有配置 ${configNo} 完全相同`;
}
