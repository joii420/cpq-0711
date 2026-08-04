import { describe, it, expect } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

describe('resolveDecimals', () => {
  it('显式 decimals 优先', () => { expect(resolveDecimals({ decimals: 3, isComputed: true })).toBe(3); });
  it('计算列未配 → 兜底 6(task-0801: DISPLAY_SCALE，原 4 位口径已作废)', () => {
    expect(resolveDecimals({ isComputed: true })).toBe(6);
  });
  it('输入/取数列未配 → null(保留原精度)', () => { expect(resolveDecimals({ isComputed: false })).toBeNull(); });
});

// task-0801（2026-08-01）：呈现精度由「至多 4 位去尾零」改为「至多 6 位去尾零」，
// 产品小计/对外导出总额不再单独固定 2 位（旧口径见 docs/RECORD.md 2026-06-21 记录，已作废）。
// 下面标注"[口径变化]"的用例期望值随之更新——都是兜底位数从 4 改到 6 引起的展示位数变化，
// 不是语义变化（HALF_UP 舍入规则、去尾零规则、null 处理均不变）。
describe('formatNumber', () => {
  it('计算列兜底 6 位、HALF_UP(task-0801 精度优先)', () => {
    // [口径变化] 0.04326/0.03414 只有 5 位小数，6 位兜底下无需舍入，原样保留（旧 4 位口径会舍入成 0.0433/0.0341）
    expect(formatNumber(0.04326, { isComputed: true })).toBe('0.04326');
    expect(formatNumber(0.03414, { isComputed: true })).toBe('0.03414');
    // 列小计 = 0.04326+0.03414 = 0.0774（精确和，6 位内原样保留）
    expect(formatNumber(0.0774, { isComputed: true })).toBe('0.0774');
    // [口径变化] 0.00005 只有 5 位小数，6 位兜底下无需舍入（旧 4 位口径会进位成 0.0001）
    expect(formatNumber(0.00005, { isComputed: true })).toBe('0.00005');
  });
  it('"至多六位"去尾零', () => {
    expect(formatNumber(0.1, { isComputed: true })).toBe('0.1');
    expect(formatNumber(0.077400, { isComputed: true })).toBe('0.0774');
    expect(formatNumber(5, { isComputed: true })).toBe('5');
  });
  it('六位以下的精确小数原样保留（新增：验证不再于 4 位处误截断，AC-1/G-3 附带场景）', () => {
    expect(formatNumber(0.0432654321, { isComputed: true })).toBe('0.043265'); // 10 位小数 → HALF_UP 到 6 位
    expect(formatNumber(0.999999, { isComputed: true })).toBe('0.999999');
  });
  it('输入/取数列保留原精度(汇率不被压)', () => {
    expect(formatNumber(6.9755, { isComputed: false })).toBe('6.9755');
  });
  it('取数列 8/12 位小数原样保留（AC-8：现网真实样本 tooling_cost 8 位 / production_energy 声明 12 位）', () => {
    expect(formatNumber(0.01333333, { isComputed: false })).toBe('0.01333333');
    expect(formatNumber(0.123456789012, { isComputed: false })).toBe('0.123456789012');
  });
  it('显式 decimals 覆盖', () => {
    expect(formatNumber(6.9755, { decimals: 4 })).toBe('6.9755');
    expect(formatNumber(6.9755, { decimals: 2 })).toBe('6.98');
  });
  it('PERCENT: *100 + %', () => {
    expect(formatNumber(0.0825, { isPercent: true, decimals: 2 })).toBe('8.25%');
  });
  it('空/非数字 → null', () => {
    expect(formatNumber('', {})).toBeNull();
    expect(formatNumber(null, {})).toBeNull();
    expect(formatNumber('abc', {})).toBeNull();
  });
  it('负数去尾零正确(不破坏符号)', () => {
    expect(formatNumber(-0.1, { isComputed: true })).toBe('-0.1');
    // [口径变化] -0.04326 只有 5 位小数，6 位兜底下无需舍入原样保留（旧 4 位口径会舍入成 -0.0433）
    expect(formatNumber(-0.04326, { isComputed: true })).toBe('-0.04326');
  });
  it('无小数点的整数尾零不被裁剪', () => {
    expect(formatNumber(1200, { isComputed: true })).toBe('1200');
    expect(formatNumber(10, { isComputed: true })).toBe('10');
  });
  it('进位跨位(HALF_UP + 去尾零, 6 位)', () => {
    // [口径变化] 0.999999 恰好 6 位小数（原样保留，不进位）；
    // 0.9999995 第 7 位是 5 → HALF_UP 进位为 1（旧用例 0.99999 在 4 位口径下会进位成 1，
    // 6 位口径下 0.99999 只有 5 位小数，原样保留不进位，故换用真正触发 6 位进位边界的值）
    expect(formatNumber(0.9999995, { isComputed: true })).toBe('1');
    expect(formatNumber(0.99999, { isComputed: true })).toBe('0.99999');
  });
});
