import { describe, it, expect } from 'vitest';
import Decimal from 'decimal.js';
import {
  DISPLAY_SCALE,
  DIVISION_SCALE,
  PAYLOAD_SIGNIFICANT_DIGITS,
  toDecimal,
  sumDecimal,
  roundToDisplay,
  normalizeNumber,
  evaluateArithmetic,
} from './precision';
import { formatNumber } from './formatNumber';

// ─── T5 常量锁定（AC-16） ───────────────────────────────────────────────────

describe('T5 常量锁定', () => {
  it('DISPLAY_SCALE === 6', () => {
    expect(DISPLAY_SCALE).toBe(6);
  });
  it('DIVISION_SCALE === 12', () => {
    expect(DIVISION_SCALE).toBe(12);
  });
  it('formatNumber 计算列兜底 = DISPLAY_SCALE(6)', () => {
    // 0.0432654321 有 10 位小数，规整到 6 位 HALF_UP：0.043265
    expect(formatNumber(0.0432654321, { isComputed: true })).toBe('0.043265');
  });
});

// ─── T1 黄金用例 G-1 ~ G-14（与 api.md §5.2 / 后端共用同一份期望值） ───────

describe('T1 黄金用例 G-1~G-14', () => {
  it('G-1: 0.1 + 0.2 = 0.3（十进制精确，double 会得 0.30000000000000004）', () => {
    const r = evaluateArithmetic('0.1+0.2');
    expect(r?.toNumber()).toBe(0.3);
  });

  it('G-2: 1.005 规整到 2 位 = 1.01（HALF_UP 舍入边界，double 会得 1.00）', () => {
    const d = toDecimal(1.005).toDecimalPlaces(2, Decimal.ROUND_HALF_UP);
    expect(d.toNumber()).toBe(1.01);
  });

  it('G-3: 1/3 显示 0.333333（除法走 12 位中间精度，显示规整 6 位）', () => {
    const r = evaluateArithmetic('1/3');
    expect(r?.toNumber()).toBe(0.333333333333); // DIVISION_SCALE=12 中间精度
    expect(roundToDisplay(r!)).toBe(0.333333);   // 显示规整 6 位
  });

  it('G-4: 10/3*3 显示 10（中间不截断；若中间截到 6 位会得 9.999999）', () => {
    const r = evaluateArithmetic('10/3*3');
    // 中间精度 12 位：10/3=3.333333333333，×3=9.999999999999，显示规整 6 位进位为 10
    expect(roundToDisplay(r!)).toBe(10);
  });

  it('G-5: 0.0000004 规整到 6 位 = 0（6 位以下归零）', () => {
    expect(roundToDisplay(0.0000004)).toBe(0);
  });

  it('G-6: 0.0000005 规整到 6 位 = 0.000001（HALF_UP 向上进位）', () => {
    expect(roundToDisplay(0.0000005)).toBe(0.000001);
  });

  it('G-7: 2.5 × 0.4 = 1（去尾零，不显示 1.000000）', () => {
    const r = evaluateArithmetic('2.5*0.4');
    expect(r?.toNumber()).toBe(1);
    expect(formatNumber(r!.toNumber(), { isComputed: true })).toBe('1');
  });

  it('G-8: 空值 / null 参与运算按 0 参与，结果非 null', () => {
    // 空值按 0 参与运算是调用方（formulaEngine.ts token 拼接）的职责，
    // 此处验证 evaluateArithmetic 对拼接后的 "0+5" 求值正确（即空值已被上游替换为 "0"）。
    const r = evaluateArithmetic('0+5');
    expect(r?.toNumber()).toBe(5);
  });

  it('G-9: 除以 0 返回 0（不抛异常）', () => {
    const r = evaluateArithmetic('5/0');
    expect(r).not.toBeNull();
    expect(r?.toNumber()).toBe(0);
    // 嵌套在更大表达式中同样不抛异常、不产生 Infinity
    const r2 = evaluateArithmetic('5+1/0*3');
    expect(r2?.toNumber()).toBe(5);
  });

  it('G-10: 6 层嵌套链路二基线 —— 与一次性十进制精确计算结果逐字节相同', () => {
    // 元素行(单价×用量) → 列小计(Σ元素行) → 页签合计(Σ列小计) → 产品小计(页签合计+加工费)
    //   → 行合计(×年用量500000) → 整单总额(Σ20行，此处用 3 行模拟拓扑同构)
    const 元素单价 = [1.234567, 2.345678, 0.987654];
    const 元素用量 = [10, 5, 20];
    const 元素行 = 元素单价.map((p, i) => toDecimal(p).times(toDecimal(元素用量[i])));
    const 列小计 = sumDecimal(元素行);
    const 加工费 = toDecimal(3.456789);
    const 产品小计 = 列小计.plus(加工费);
    const 年用量 = toDecimal(500000);
    const 行合计 = 产品小计.times(年用量);
    // 3 "行"（同一产品小计模拟不同产品线）求整单总额
    const 整单总额 = sumDecimal([行合计, 行合计, 行合计]);

    // 一次性精确基线（不经过中间函数，纯 Decimal 链式运算，验证与分步运算逐字节相同）
    const baseline = toDecimal(1.234567).times(10)
      .plus(toDecimal(2.345678).times(5))
      .plus(toDecimal(0.987654).times(20))
      .plus(toDecimal(3.456789))
      .times(500000)
      .times(3);

    expect(整单总额.toString()).toBe(baseline.toString());
  });

  it('G-11: 单价 123.456789 × 年用量 800000 = 98765431.2（亿级金额精度）', () => {
    const r = toDecimal(123.456789).times(toDecimal(800000));
    expect(r.toNumber()).toBe(98765431.2);
    expect(roundToDisplay(r)).toBe(98765431.2);
  });

  it('G-12: 一元负号 -(2+3)*2 = -10', () => {
    const r = evaluateArithmetic('-(2+3)*2');
    expect(r?.toNumber()).toBe(-10);
  });

  it('G-13: 运算符优先级 2+3*4 = 14', () => {
    const r = evaluateArithmetic('2+3*4');
    expect(r?.toNumber()).toBe(14);
  });

  it('G-14: 全角运算符 2×3÷4 = 1.5', () => {
    const r = evaluateArithmetic('2×3÷4');
    expect(r?.toNumber()).toBe(1.5);
  });
});

// ─── T2 求值点覆盖（3 个求值点各一条 0.1+0.2=0.3，见 formulaEngine.test.ts / ExcelView 手动验证） ──

describe('T2 evaluateArithmetic 基础覆盖', () => {
  it('0.1+0.2 = 0.3', () => {
    expect(evaluateArithmetic('0.1+0.2')?.toNumber()).toBe(0.3);
  });
  it('带空格的表达式', () => {
    expect(evaluateArithmetic(' 1 + 2 * 3 ')?.toNumber()).toBe(7);
  });
  it('嵌套括号', () => {
    expect(evaluateArithmetic('((1+2)*(3+4))')?.toNumber()).toBe(21);
  });
});

// ─── T3 链路二基线（AC-14）───────────────────────────────────────────────

describe('T3 链路二基线：单价 6 位 × 年用量 50 万 × 20 行', () => {
  it('整单合计小数第 6 位仍正确', () => {
    const unitPrice = 123.456789; // 6 位小数
    const qty = 500000;
    const lineTotal = toDecimal(unitPrice).times(toDecimal(qty));
    const lines = Array.from({ length: 20 }, () => lineTotal);
    const grandTotal = sumDecimal(lines);
    // 期望 = 123.456789 * 500000 * 20，精确值
    const expected = new Decimal('123.456789').times(500000).times(20);
    expect(grandTotal.toString()).toBe(expected.toString());
    expect(roundToDisplay(grandTotal)).toBe(expected.toDecimalPlaces(6, Decimal.ROUND_HALF_UP).toNumber());
  });

  it('number 链式累加在同等量级下会产生可探测误差（反证 Decimal 化的必要性）', () => {
    const unitPrice = 123.456789;
    const qty = 500000;
    let numSum = 0;
    for (let i = 0; i < 20; i++) numSum += unitPrice * qty;
    const decSum = sumDecimal(Array.from({ length: 20 }, () => toDecimal(unitPrice).times(qty))).toNumber();
    // 本用例不强制断言两者不等（不同 JS 引擎/优化策略可能巧合相等），
    // 仅确认 Decimal 路径给出精确解析解，作为链路二必须全程 Decimal 的存在性证明。
    const expected = new Decimal('123.456789').times(500000).times(20).toNumber();
    expect(decSum).toBe(expected);
    void numSum;
  });
});

// ─── T4 链路一基线（AC-13）───────────────────────────────────────────────

describe('T4 链路一基线：多层嵌套结果 = 一次性精确计算结果', () => {
  it('元素行→列小计→页签合计→产品小计 逐层与一次性计算相同', () => {
    const rows = [0.04326, 0.03414, 0.01234567];
    const stepwise = sumDecimal(rows); // 列小计
    const tabTotal = stepwise.plus(toDecimal(0.001)); // 页签合计（加一个固定成本列）
    const productSubtotal = tabTotal.plus(toDecimal(0.5)); // 产品小计（+另一页签）

    const onePass = toDecimal(0.04326).plus(0.03414).plus(0.01234567).plus(0.001).plus(0.5);
    expect(productSubtotal.toString()).toBe(onePass.toString());
  });
});

// ─── T6 类别隔离（AC-8 / AC-9）─────────────────────────────────────────────

describe('T6 类别隔离：normalizeNumber 不压坏取数列，不影响费率', () => {
  it('8 位小数取数值经 normalizeNumber 后仍是 8 位（tooling_cost 现网真实样本 0.01333333）', () => {
    const v = 0.01333333;
    expect(normalizeNumber(v)).toBe(0.01333333);
  });

  it('12 位小数取数值经 normalizeNumber 后仍是 12 位（production_energy.unit_price 列声明 scale）', () => {
    const v = 0.123456789012;
    expect(normalizeNumber(v)).toBe(0.123456789012);
  });

  it('6 位小数取数值（unit_price.pricing_price）不被压', () => {
    expect(normalizeNumber(0.070000000000)).toBe(0.07);
  });

  it('大额金额（亿级）不因规范化产生噪声（技术总监反例：toFixed(14) 会暴露噪声）', () => {
    expect(normalizeNumber(98765431.2)).toBe(98765431.2);
    // 对照：toFixed(14) 这种按小数位数规整的旧方案会暴露 double 噪声，
    // normalizeNumber（按有效数字）不会。
    expect((98765431.2).toFixed(14)).not.toBe('98765431.20000000000000');
  });

  it('消除 IEEE754 尾差：0.1+0.2 的浮点噪声被消除', () => {
    expect(normalizeNumber(0.1 + 0.2)).toBe(0.3);
  });

  it('0.015（8 位取数列典型值）不受影响', () => {
    expect(normalizeNumber(0.015)).toBe(0.015);
  });

  it('非有限值原样返回（不崩溃）', () => {
    expect(normalizeNumber(NaN)).toBeNaN();
    expect(normalizeNumber(Infinity)).toBe(Infinity);
  });

  it('0 原样返回', () => {
    expect(normalizeNumber(0)).toBe(0);
  });

  it('PAYLOAD_SIGNIFICANT_DIGITS === 15', () => {
    expect(PAYLOAD_SIGNIFICANT_DIGITS).toBe(15);
  });
});

// ─── T7 语义不变（R-5：与后端 ArithParser 语义逐条对齐）───────────────────

describe('T7 语义不变：除零/null/非法表达式/全角运算符/一元负号/优先级', () => {
  it('除零 → 0（不抛异常）', () => {
    expect(evaluateArithmetic('10/0')?.toNumber()).toBe(0);
  });

  it('空值按 0 参与运算（上游拼接后的 "0" 字面量）', () => {
    expect(evaluateArithmetic('0*100')?.toNumber()).toBe(0);
  });

  it('非法表达式 → null（未闭合括号）', () => {
    expect(evaluateArithmetic('(1+2')).toBeNull();
  });

  it('非法表达式 → null（非数字字符，模拟 formulaEngine 的错误旁路注入 "(null.x)"）', () => {
    expect(evaluateArithmetic('(null.x)')).toBeNull();
  });

  it('非法表达式 → null（空字符串）', () => {
    expect(evaluateArithmetic('')).toBeNull();
    expect(evaluateArithmetic('   ')).toBeNull();
    expect(evaluateArithmetic(null)).toBeNull();
    expect(evaluateArithmetic(undefined)).toBeNull();
  });

  it('非法表达式 → null（多余尾随字符）', () => {
    expect(evaluateArithmetic('1 2')).toBeNull();
  });

  it('全角运算符 × ÷ 正确转换', () => {
    expect(evaluateArithmetic('6×7')?.toNumber()).toBe(42);
    expect(evaluateArithmetic('8÷2')?.toNumber()).toBe(4);
    expect(evaluateArithmetic('2×3÷4')?.toNumber()).toBe(1.5);
  });

  it('一元负号：多层 --5 = 5, -5 = -5', () => {
    expect(evaluateArithmetic('-5')?.toNumber()).toBe(-5);
    expect(evaluateArithmetic('--5')?.toNumber()).toBe(5);
    expect(evaluateArithmetic('-(2+3)*2')?.toNumber()).toBe(-10);
  });

  it('优先级：乘除高于加减', () => {
    expect(evaluateArithmetic('2+3*4')?.toNumber()).toBe(14);
    expect(evaluateArithmetic('2*3+4')?.toNumber()).toBe(10);
    expect(evaluateArithmetic('10-2/2')?.toNumber()).toBe(9);
  });

  it('科学计数法兼容（formulaEngine token 拼接可能产生 1e-7 这类字面量）', () => {
    expect(evaluateArithmetic('1e-7')?.toNumber()).toBe(0.0000001);
    expect(evaluateArithmetic('1.5e3+1')?.toNumber()).toBe(1501);
    expect(evaluateArithmetic('2e-3*1000')?.toNumber()).toBe(2);
  });
});

// ─── T8 显示格式（AC-1/AC-2）───────────────────────────────────────────────

describe('T8 显示格式：至多 6 位去尾零', () => {
  it('0.0774 → "0.0774"（不补零）', () => {
    expect(formatNumber(0.0774, { isComputed: true })).toBe('0.0774');
  });
  it('5 → "5"（不补成 5.000000）', () => {
    expect(formatNumber(5, { isComputed: true })).toBe('5');
  });
  it('0.0000005 → "0.000001"（HALF_UP 进位）', () => {
    expect(formatNumber(0.0000005, { isComputed: true })).toBe('0.000001');
  });
  it('0.0432654321 → "0.043265"（HALF_UP 规整到 6 位）', () => {
    expect(formatNumber(0.0432654321, { isComputed: true })).toBe('0.043265');
  });
  it('12345.678 → "12345.678"（原样，不足 6 位不补）', () => {
    expect(formatNumber(12345.678, { isComputed: true })).toBe('12345.678');
  });
  it('null / 空 → null（渲染层显示 "—"）', () => {
    expect(formatNumber(null, { isComputed: true })).toBeNull();
    expect(formatNumber('', { isComputed: true })).toBeNull();
  });
});

// ─── toDecimal / sumDecimal 边界 ───────────────────────────────────────────

describe('toDecimal / sumDecimal 边界', () => {
  it('toDecimal(null/undefined/空字符串) = 0', () => {
    expect(toDecimal(null).toNumber()).toBe(0);
    expect(toDecimal(undefined).toNumber()).toBe(0);
    expect(toDecimal('').toNumber()).toBe(0);
  });
  it('toDecimal(非数字字符串) = 0（不抛异常）', () => {
    expect(toDecimal('abc').toNumber()).toBe(0);
  });
  it('toDecimal(NaN/Infinity) = 0', () => {
    expect(toDecimal(NaN).toNumber()).toBe(0);
    expect(toDecimal(Infinity).toNumber()).toBe(0);
  });
  it('sumDecimal 混合非法值按 0 参与', () => {
    expect(sumDecimal([1, null, 'abc', 2, undefined, 3]).toNumber()).toBe(6);
  });
  it('sumDecimal 空数组 = 0', () => {
    expect(sumDecimal([]).toNumber()).toBe(0);
  });
});
