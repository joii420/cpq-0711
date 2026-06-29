/**
 * formatPathValue.ts — 独立模块（从 ComponentCell.tsx 抽出）
 *
 * 抽出原因：inputDefaults.ts 需要使用 formatPathValue，同时 ComponentCell 也需要
 * 使用 inputDefaults，若二者互相 import 会形成循环依赖（Vite ESM AP-43 问题）。
 * 将 formatPathValue 独立成此模块，断开该循环。
 */
import { formatNumber } from '../../../utils/formatNumber';

// ─── ENUM 标签（与 QuotationStep2 同源） ────────────────────────────────────
const ENUM_LABEL: Record<string, string> = {
  INCOMING_FIXED: '来料固定加工费',
  INCOMING_OTHER: '来料其他费用',
  FINISHED_FIXED: '成品固定加工费',
  FINISHED_OTHER: '成品其他费用',
  INCOMING_ANNUAL_DOWN: '来料年降',
  ASSEMBLY_PROCESS: '组装加工费',
  ASSEMBLY_ANNUAL_DOWN: '组装加工费年降',
  ANNUAL_REDUCTION_FACTOR: '年降系数',
  ELEMENT: '元素 BOM',
  INCOMING: '来料 BOM',
  ASSEMBLY: '组装 BOM',
};

/**
 * 把后端 path 求值结果投影成显示字符串。
 * 与 QuotationStep2.formatPathValue 完全同源。
 *
 * @param decimals 数值显示位数（来自字段 decimals）。null/undefined = 未配 →
 *                 formatNumber 保留原精度（如汇率 6.9755 不被截断）。仅作用于
 *                 number 分支；ENUM/array/jsonb 等分支不受影响。
 */
export function formatPathValue(v: any, decimals?: number | null): string | null {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return formatNumber(v, { decimals }) ?? String(v);
  if (typeof v === 'boolean') return String(v);
  if (typeof v === 'string') return ENUM_LABEL[v] ?? v;
  if (Array.isArray(v)) {
    if (v.length === 0) return null;
    const first = formatPathValue(v[0]);
    if (v.length === 1) return first;
    return first ? `${first} (共 ${v.length} 项)` : `共 ${v.length} 项`;
  }
  if (typeof v === 'object') {
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = JSON.parse(v.value);
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) return null;
          const items = parsed.map((it: any) => formatPathValue(it) ?? '').filter(Boolean);
          return items.length > 0 ? items.join(', ') : null;
        }
        if (parsed && typeof parsed === 'object') {
          const keys = Object.keys(parsed);
          if (keys.length === 0) return null;
          const pairs = keys.map((k: string) => {
            const sub = formatPathValue(parsed[k]);
            return sub != null ? `${k}=${sub}` : null;
          }).filter(Boolean);
          return pairs.length > 0 ? pairs.join(', ') : null;
        }
        return formatPathValue(parsed);
      } catch {
        return v.value;
      }
    }
    for (const k of Object.keys(v)) {
      if (v[k] != null && v[k] !== '') {
        const sub = formatPathValue(v[k]);
        if (sub) return sub;
      }
    }
    return null;
  }
  return String(v);
}
