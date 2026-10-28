import { describe, expect, it } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

type NumberOptions = {
  decimals?: number;
  isComputed?: boolean;
  isPercent?: boolean;
  maxDisplayDecimals?: number;
};

const SALES_LIMIT = 4;
const OTHER_LIMIT = 9;

/**
 * D-1 规定通用格式化规格增加“可选上限”，同时省略它保持既有最多 9 位。
 * 通过适配器调用公开函数可避免把测试绑到报价组件内部状态或认证 store 实现。
 */
function formatWithLimit(value: string, options: NumberOptions, maxDecimals: number): string | null {
  return formatNumber(value, { ...options, maxDisplayDecimals: maxDecimals });
}

function decimalsWithLimit(options: NumberOptions, maxDecimals: number): number | null {
  return resolveDecimals({ ...options, maxDisplayDecimals: maxDecimals });
}

describe('T261027_RDP_ · 前端角色显示精度纯函数契约', () => {
  it('T261027_RDP_UNIT_FE_01 / AC-3：显式 2/6 位与角色上限取较小值', () => {
    const value = '1.2345678915';

    expect(decimalsWithLimit({ decimals: 2, isComputed: true }, SALES_LIMIT)).toBe(2);
    expect(decimalsWithLimit({ decimals: 2, isComputed: true }, OTHER_LIMIT)).toBe(2);
    expect(decimalsWithLimit({ decimals: 6, isComputed: true }, SALES_LIMIT)).toBe(4);
    expect(decimalsWithLimit({ decimals: 6, isComputed: true }, OTHER_LIMIT)).toBe(6);

    expect(formatWithLimit(value, { decimals: 2, isComputed: true }, SALES_LIMIT)).toBe('1.23');
    expect(formatWithLimit(value, { decimals: 2, isComputed: true }, OTHER_LIMIT)).toBe('1.23');
    expect(formatWithLimit(value, { decimals: 6, isComputed: true }, SALES_LIMIT)).toBe('1.2346');
    expect(formatWithLimit(value, { decimals: 6, isComputed: true }, OTHER_LIMIT)).toBe('1.234568');
  });

  it('T261027_RDP_UNIT_FE_02 / AC-6：销售角色保留 HALF_UP、尾零、负数和负零语义', () => {
    const computed = { isComputed: true };
    expect(formatWithLimit('12.500000000000', computed, SALES_LIMIT)).toBe('12.5');
    expect(formatWithLimit('1.23444', computed, SALES_LIMIT)).toBe('1.2344');
    expect(formatWithLimit('1.23445', computed, SALES_LIMIT)).toBe('1.2345');
    expect(formatWithLimit('-1.23444', computed, SALES_LIMIT)).toBe('-1.2344');
    expect(formatWithLimit('-1.23445', computed, SALES_LIMIT)).toBe('-1.2345');
    expect(formatWithLimit('-0.00001', computed, SALES_LIMIT)).toBe('0');
  });

  it.each(['用户输入数字', '汇率', '税率', '利润快照', '基础资料取数值'])(
    'T261027_RDP_UNIT_FE_03 / AC-9：%s 不套销售 4 位上限',
    (semantic) => {
      const raw = '1.234567891500';
      const beforeRoleLimit = formatNumber(raw, { isComputed: false });
      expect(
        formatWithLimit(raw, { isComputed: false }, SALES_LIMIT),
        `${semantic} 加入销售角色上限后的显示文本必须与改造前一致`,
      ).toBe(beforeRoleLimit);
      expect(beforeRoleLimit, `${semantic} 的既有通用显示仍允许去尾零`).toBe('1.2345678915');
    },
  );

  it('T261027_RDP_UNIT_FE_04 / AC-9：计算值受限而省略新参数的报价外调用仍最多 9 位', () => {
    const raw = '1.2345678915';
    expect(formatWithLimit(raw, { isComputed: true }, SALES_LIMIT)).toBe('1.2346');
    expect(formatNumber(raw, { isComputed: true })).toBe('1.234567892');
    expect(formatNumber(raw, { isComputed: false })).toBe(raw);
  });
});
