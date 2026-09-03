/**
 * 材质占比的**定点整数**判据（task-260902 · F-5，服务 AC-4 / AC-15a / AC-15b）。
 *
 * 🚨 **合计必须用定点整数运算，🚫 不许 `Number` 累加。**
 *
 * 为什么不能用 `Number`（这是本模块存在的唯一理由，改之前先读完）：
 *   - AC-15a `33.333333333333 + 33.333333333333 + 33.333333333334`
 *     在 JS 浮点下**恰好等于 100** ⇒ 这组数据**拦不住浮点实现**，用它自测等于没测。
 *   - AC-15b `0.000000000001 + 99.999999999998 + 0.000000000001`
 *     在 JS 浮点下等于 `99.99999999999999` ⇒ 浮点实现会**错误拒绝一个合法输入**。
 *   ⇒ **两条都过才算对**。本模块把占比按 12 位小数换算成 `BigInt` 再累加，两条都是精确的。
 *
 * 为什么不直接用 `decimal.js`（项目里已有 `utils/precision.ts`）：
 *   decimal.js 也是精确十进制、结论相同，但它是**可配置精度**的（`Decimal.set({precision:80})` 在
 *   `utils/precision.ts` 里是全局副作用）。占比判等是「必须逐位精确」的口径，与库列
 *   `numeric(24,12)` 一一对应，用 `BigInt` 表达 12 位定点没有任何可配置项可以被别处改坏。
 *
 * 🚨 本模块**只做判等与提示**，不改变提交值：提交时占比按用户输入的**原始字符串**原样发送
 *    （不补零、不做数值转换），与 `api.md §1.2` 一致。
 */
import { trimTrailingZeros } from '../../../utils/precision';

/** 与库列 `material_bom_item.material_ratio` 的 `numeric(24,12)` 同口径。 */
export const RATIO_SCALE = 12;

const SCALE_FACTOR = 10n ** BigInt(RATIO_SCALE);
/** 100%（定点整数形态）。 */
export const RATIO_TARGET = 100n * SCALE_FACTOR;

/** 纯十进制串（不接受科学计数法、不接受负号 —— 占比没有负数）。 */
const PLAIN_NON_NEGATIVE = /^\+?(\d+)(?:\.(\d*))?$|^\+?\.(\d+)$/;

export type RatioParseError = 'EMPTY' | 'NOT_A_NUMBER' | 'TOO_MANY_DECIMALS' | 'OUT_OF_RANGE';

export interface RatioParseResult {
  /** 定点整数形态（× 10^12）。解析失败为 null。 */
  scaled: bigint | null;
  error: RatioParseError | null;
}

/**
 * 把占比字符串换算成定点整数。
 * 🚫 全程不经过 JS `number` —— 只做字符串切分 + `BigInt`。
 */
export function parseRatio(text: string | null | undefined): RatioParseResult {
  const raw = (text ?? '').trim();
  if (raw === '') return { scaled: null, error: 'EMPTY' };
  const m = PLAIN_NON_NEGATIVE.exec(raw);
  if (!m) return { scaled: null, error: 'NOT_A_NUMBER' };
  const intPart = m[1] ?? '0';
  const fracPart = m[2] ?? m[3] ?? '';
  // 超过 12 位小数存不进 numeric(24,12)，落库会被静默四舍五入 ⇒ 当场拒绝，不让它默默变值。
  if (fracPart.length > RATIO_SCALE) return { scaled: null, error: 'TOO_MANY_DECIMALS' };
  const padded = (fracPart + '0'.repeat(RATIO_SCALE)).slice(0, RATIO_SCALE);
  const scaled = BigInt(intPart) * SCALE_FACTOR + BigInt(padded || '0');
  // 单值必须 > 0 且 ≤ 100：占比 0 的材质等于没加，负数不存在，>100 单条就已经不可能凑成 100。
  if (scaled <= 0n || scaled > RATIO_TARGET) return { scaled: null, error: 'OUT_OF_RANGE' };
  return { scaled, error: null };
}

/** 定点整数 → 展示文本（去尾随零，走 `trimTrailingZeros` 的字符串正则，🚫 不过 `Number`）。 */
export function formatScaledRatio(scaled: bigint): string {
  return formatScaled(scaled, RATIO_SCALE);
}

/** 通用定点整数 → 展示文本（`scale` = 小数位数）。 */
function formatScaled(scaled: bigint, scale: number): string {
  const factor = 10n ** BigInt(scale);
  const negative = scaled < 0n;
  const abs = negative ? -scaled : scaled;
  const intPart = (abs / factor).toString();
  const fracPart = (abs % factor).toString().padStart(scale, '0');
  return (negative ? '-' : '') + trimTrailingZeros(`${intPart}.${fracPart}`);
}

export function ratioErrorText(error: RatioParseError): string {
  switch (error) {
    case 'EMPTY': return '请填写占比';
    case 'NOT_A_NUMBER': return '占比只能填数字';
    case 'TOO_MANY_DECIMALS': return `占比最多 ${RATIO_SCALE} 位小数`;
    case 'OUT_OF_RANGE': return '占比必须大于 0 且不超过 100';
    default: return '占比不合法';
  }
}

export interface RatioSumResult {
  /** 合计（定点整数）。非法行按 0 计入，不会让合计"看起来正好 100"。 */
  scaledSum: bigint;
  /** 合计的展示文本，如 `90` / `100` / `99.999999999999`。 */
  sumText: string;
  /** 合计是否**恰好** 100（定点全等，无容差 —— 占比不是含量，没有 ±2 的容差口径）。 */
  ok: boolean;
  /** 每行的解析错误（与入参同序，null = 合法）。 */
  rowErrors: Array<RatioParseError | null>;
  /** 有任何一行本身不合法。 */
  hasInvalidRow: boolean;
}

export function sumRatios(ratios: ReadonlyArray<string | null | undefined>): RatioSumResult {
  let scaledSum = 0n;
  const rowErrors: Array<RatioParseError | null> = [];
  let hasInvalidRow = false;
  for (const r of ratios) {
    const parsed = parseRatio(r);
    rowErrors.push(parsed.error);
    if (parsed.error) hasInvalidRow = true;
    else scaledSum += parsed.scaled!;
  }
  return {
    scaledSum,
    sumText: formatScaledRatio(scaledSum),
    ok: !hasInvalidRow && scaledSum === RATIO_TARGET,
    rowErrors,
    hasInvalidRow,
  };
}

/**
 * AC-4 断言的提示文案：**必须写出实际合计值**，不许「合计不正确」这种形容词。
 * 行级消息带差额（原型 3 状态 B：「材质占比合计为 **90%**，需要正好 100%（还差 10%）」）。
 */
export function ratioSumMessage(result: RatioSumResult, withDelta = true): string {
  const base = `材质占比合计为 ${result.sumText}%，需要正好 100%`;
  if (!withDelta) return base;
  const diff = RATIO_TARGET - result.scaledSum;
  if (diff === 0n) return base;
  return diff > 0n
    ? `${base}（还差 ${formatScaledRatio(diff)}%）`
    : `${base}（超出 ${formatScaledRatio(-diff)}%）`;
}

/**
 * 折合克重 = 总重 × 占比 ÷ 100（AC-3 / F-4，实时计算、只读展示）。
 *
 * 🚫 同样不过 `number`：总重与占比都按定点整数相乘，再按 `RATIO_SCALE` 位截断展示。
 * 总重或占比任一不合法 → 返回 `null`，调用方渲染「—」（原型 3 状态 B 的错误态就是「—」）。
 */
export function computeGramsByRatio(
  totalWeight: string | null | undefined,
  ratio: string | null | undefined,
): string | null {
  const w = parseWeight(totalWeight);
  const r = parseRatio(ratio);
  if (w === null || r.error) return null;
  // w、r 都是 ×10^12 的定点数 ⇒ 乘积是 ×10^24，再除 100 得到 ×10^24 的「克 × 100」；
  // 统一回到 ×10^12：product / (100 * 10^12)。整除会截断超出 12 位的部分，与库列口径一致。
  // w、r 各是 ×10^12 ⇒ product 是 ×10^24 的「克 × 100」。除以 100（整数除，无损）后
  // 仍是 ×10^24 的克数 ⇒ 按 24 位定点格式化再去尾随零，得到**精确值**。
  // 🚫 不在这里截到 12 位：AC-15b 那组占比 0.000000000001 的克重是 1e-13 g，
  //    截到 12 位会显示成 `0`——一个非零占比显示成 0 克是误导，比多几位小数糟糕得多。
  const product = (w * r.scaled!) / 100n;
  return formatScaled(product, RATIO_SCALE * 2);
}

/** 总重：允许 ≥ 0 的任意 12 位小数；0 与空由调用方按「必填且 > 0」另行拦（AC-3 `PART_WEIGHT_REQUIRED`）。 */
export function parseWeight(text: string | null | undefined): bigint | null {
  const raw = (text ?? '').trim();
  if (raw === '') return null;
  const m = PLAIN_NON_NEGATIVE.exec(raw);
  if (!m) return null;
  const intPart = m[1] ?? '0';
  const fracPart = m[2] ?? m[3] ?? '';
  if (fracPart.length > RATIO_SCALE) return null;
  const padded = (fracPart + '0'.repeat(RATIO_SCALE)).slice(0, RATIO_SCALE);
  return BigInt(intPart) * SCALE_FACTOR + BigInt(padded || '0');
}

/** 总重是否合法（必填且 > 0）。 */
export function isWeightValid(text: string | null | undefined): boolean {
  const w = parseWeight(text);
  return w !== null && w > 0n;
}
