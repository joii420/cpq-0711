// ─────────────────────────────────────────────────────────────────────────────
// 产品管理页（task-260903）· 单元格渲染原语
//
// 三条纪律集中在这里，避免每个表各写一套：
//   1. 空值（null / undefined / ''）一律渲染 `—`，🚫 不许空白、不许 `undefined`（AC-2）
//   2. 数值**后端以字符串回传保留 scale**，🚫 禁止 `Number()` 转换后再格式化（丢精度）
//      —— 走 `utils/precision.ts` 的 `formatDisplayDecimal`（Decimal.js，不经 double）
//   3. 超长文案省略号截断 + hover 显示完整值（AC-15）
// ─────────────────────────────────────────────────────────────────────────────
import React from 'react';
import { formatDisplayDecimal, isDecimalString } from '../../utils/precision';

/** 空值占位符。全页统一，勿各处写死。 */
export const DASH = '—';

/** 空值/占位的灰度，取自原型 `.muted { color: rgba(0,0,0,.25) }` */
const MUTED: React.CSSProperties = { color: 'rgba(0, 0, 0, 0.25)' };

/** 数值列等宽数字，便于纵向比对位数（原型 `font-variant-numeric: tabular-nums`） */
const TABULAR: React.CSSProperties = { fontVariantNumeric: 'tabular-nums' };

function isBlank(v: unknown): boolean {
  return v === null || v === undefined || v === '';
}

/**
 * 文本单元格：有值显示原值（hover 出完整值），空值显示灰色 `—`。
 * 🚫 不做任何数值格式化 —— `type=STRING` 但值像数字的列（如 `pricing_unit` 计价单位，
 *    值是 `PCS` / `g` 这类文本）必须原样左对齐显示。
 */
export function renderTextCell(v: unknown): React.ReactNode {
  if (isBlank(v)) return <span style={MUTED}>{DASH}</span>;
  const text = String(v);
  return <span title={text}>{text}</span>;
}

/**
 * 数值单元格：走 Decimal.js 的 `formatDisplayDecimal`（DISPLAY_SCALE=9，去尾零）。
 *
 * ⚠️ 兜底一条：值不是合法十进制字符串时（脏数据 / 后端类型标错）**原样按文本显示**，
 *    不走格式化 —— `formatDisplayDecimal` 内部 `toDecimal` 对非法输入返回 0，
 *    直接格式化会把 `"N/A"` 静默显示成 `0`，那是把脏数据伪装成合法数值。
 */
export function renderDecimalCell(v: unknown): React.ReactNode {
  if (isBlank(v)) return <span style={{ ...MUTED, ...TABULAR }}>{DASH}</span>;
  const raw = typeof v === 'string' ? v : String(v);
  if (!isDecimalString(raw)) return <span title={raw}>{raw}</span>;
  const text = formatDisplayDecimal(raw);
  return <span style={TABULAR} title={raw}>{text}</span>;
}

/** 布尔单元格：是 / 否 / `—` */
export function renderBooleanCell(v: unknown): React.ReactNode {
  if (isBlank(v)) return <span style={MUTED}>{DASH}</span>;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (s === 'true' || s === '1') return <span>是</span>;
    if (s === 'false' || s === '0') return <span>否</span>;
    return <span title={v}>{v}</span>;
  }
  return <span>{v ? '是' : '否'}</span>;
}
