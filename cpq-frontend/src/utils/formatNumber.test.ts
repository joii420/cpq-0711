import { describe, it, expect } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

describe('resolveDecimals', () => {
  it('显式 decimals 优先', () => { expect(resolveDecimals({ decimals: 3, isComputed: true })).toBe(3); });
  it('计算列未配 → 兜底 4(精度优先)', () => { expect(resolveDecimals({ isComputed: true })).toBe(4); });
  it('输入/取数列未配 → null(保留原精度)', () => { expect(resolveDecimals({ isComputed: false })).toBeNull(); });
});

describe('formatNumber', () => {
  it('计算列兜底 4 位、HALF_UP(精度优先)', () => {
    // 复现 0.04+0.03=0.08 问题：原 2 位会把 0.04326/0.03414 压成 0.04/0.03，4 位保留真值
    expect(formatNumber(0.04326, { isComputed: true })).toBe('0.0433');
    expect(formatNumber(0.03414, { isComputed: true })).toBe('0.0341');
    // 列小计 = 0.04326+0.03414 = 0.0774（4 位真值，不再显示成 0.08）
    expect(formatNumber(0.0774, { isComputed: true })).toBe('0.0774');
    expect(formatNumber(0.00005, { isComputed: true })).toBe('0.0001');
  });
  it('"最多四位"去尾零', () => {
    expect(formatNumber(0.1, { isComputed: true })).toBe('0.1');
    expect(formatNumber(0.077400, { isComputed: true })).toBe('0.0774');
    expect(formatNumber(5, { isComputed: true })).toBe('5');
  });
  it('输入/取数列保留原精度(汇率不被压)', () => {
    expect(formatNumber(6.9755, { isComputed: false })).toBe('6.9755');
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
    expect(formatNumber(-0.04326, { isComputed: true })).toBe('-0.0433');
  });
  it('无小数点的整数尾零不被裁剪', () => {
    expect(formatNumber(1200, { isComputed: true })).toBe('1200');
    expect(formatNumber(10, { isComputed: true })).toBe('10');
  });
  it('进位跨位(HALF_UP + 去尾零, 4 位)', () => {
    expect(formatNumber(0.99999, { isComputed: true })).toBe('1');
  });
});
