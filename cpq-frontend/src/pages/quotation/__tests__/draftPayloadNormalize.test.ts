import { describe, it, expect } from 'vitest';
import { normalizeDraftPayloadNumbers } from '../QuotationWizard';

/**
 * task-0801（2026-08-01，二次修订）：normalizeDraftPayloadNumbers 的规范化机制从
 * `Number(v.toFixed(4))`（按小数位数）改为 `normalizeNumber`（按有效数字 15 位，见
 * precision.ts 的 PAYLOAD_SIGNIFICANT_DIGITS 注释）。
 *
 * 本文件下方标注 [语义变化，非 bug] 的用例是**故意**改写，不是误改：
 * 旧机制按"小数位数"一刀切，会把两个在 1e-11 量级上真实不同的数值粗暴撞成相同——这不是消除
 * 浮点噪声，而是丢精度（若这两个值来自两条真实不同的计算路径，1e-11 的差异在旧机制下会被
 * 悄悄抹掉）。改造后的 Decimal 精确公式引擎（task-0801 F1/F2）本身就不再产生这种量级的计算路径
 * 分叉噪声，`normalizeDraftPayloadNumbers` 现在只需处理更小量级的 IEEE754 表示噪声
 * （典型如 `0.1+0.2` = `0.30000000000000004`，噪声在第 16~17 位有效数字），因此改用
 * 有效数字规整反而更贴合现在的真实需求：既消除这类表示噪声，又不牺牲取数列的精度（AC-8）。
 */
describe('normalizeDraftPayloadNumbers — 浮点尾差规范化（task-0801 改按有效数字）', () => {
  it('IEEE754 表示噪声（0.1+0.2 型）被消除', () => {
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0.1 + 0.2, rowData: [] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0.3, rowData: [] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
    expect(a.lineItems[0].subtotal).toBe(0.3);
  });

  it('rowData 内数值字段同样按有效数字规范化（IEEE754 尾差量级）', () => {
    const noisy = 1.15 + Number.EPSILON * 4; // 极小量级尾差（表示噪声，非计算路径分叉）
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: noisy }] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: 1.15 }] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });

  it('[语义变化，非 bug] 1e-11 量级的真实数值差异不再被强行撞成相同', () => {
    // 旧机制（toFixed(4)）会把这两个值都压成 25.4100，视为"相同"；
    // 新机制（有效数字 15 位）保留这个量级的差异 —— 这不是回归，而是本次改造刻意的取舍：
    // 不能用"按小数位数一刀切"的粗暴手段消噪，否则会同时压坏取数列（AC-8 的核心诉求）。
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.40999999998, rowData: [] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.41000000001, rowData: [] }] });
    expect(a.lineItems[0].subtotal).not.toBe(b.lineItems[0].subtotal);
  });

  it('非数值字段原样保留', () => {
    const r = normalizeDraftPayloadNumbers({ name: '料8', lineItems: [{ subtotal: 1, rowData: [{ 料件: '料8' }] }] });
    expect(r.name).toBe('料8');
    expect(r.lineItems[0].rowData[0]['料件']).toBe('料8');
  });

  it('AC-8：8 位小数取数列（tooling_cost 现网真实样本）规范化后仍是 8 位', () => {
    const r = normalizeDraftPayloadNumbers({ lineItems: [{ rowData: [{ tooling_unit_price: 0.01333333 }] }] });
    expect(r.lineItems[0].rowData[0].tooling_unit_price).toBe(0.01333333);
  });

  it('AC-8：12 位小数取数列（production_energy.unit_price 列声明精度）规范化后仍是 12 位', () => {
    const r = normalizeDraftPayloadNumbers({ lineItems: [{ rowData: [{ unit_price: 0.123456789012 }] }] });
    expect(r.lineItems[0].rowData[0].unit_price).toBe(0.123456789012);
  });

  it('大额金额（亿级）不因规范化产生 toFixed(14) 式的噪声暴露', () => {
    const r = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 98765431.2 }] });
    expect(r.lineItems[0].subtotal).toBe(98765431.2);
  });
});
