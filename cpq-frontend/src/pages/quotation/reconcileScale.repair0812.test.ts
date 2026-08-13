/**
 * repair-0812 —— 对账阈值与结果尺度不对称致误报
 *
 * 守的缺陷：前端 formulaCache 是 12 位工作值（CALCULATION_SCALE），后端快照
 * formulaResults 是 9 位结果值（FORMULA_RESULT_SCALE）——两侧尺度天然差 3 位，
 * 差值上界 5e-10，而 `isWithinTolerance` 默认容差 1e-12 比它小 500 倍，
 * 不归一直接比较 ⇒ 凡工作值第 10~12 位非零必然误报（TC-01 是本 BUG 的现场值）。
 *
 * 修复：`valuesReconcile` 比较前先把两侧按 `formatFormulaResult`（9 位）归一，
 * 再交给 `isWithinTolerance`（容差仍用默认 1e-12，只吸收同尺度末位噪声）。
 *
 * 本文件直接 import 生产代码里的 `valuesReconcile`（已从组件内局部闭包挪到模块
 * 作用域并导出，见 QuotationStep2.tsx#precisionValue 之后的定义）——测的是真实
 * 生产逻辑，非等价复现。
 *
 * 用例书：dev-docs/task-0801-公式计算精度优化/repair-0812-对账阈值与结果尺度不对称致误报/test.md §2
 * TC-01 即 task-0806 test.md TC-121（阈值负例，当初标"未执行"）的补齐。
 */
import { describe, it, expect } from 'vitest';
import { valuesReconcile } from './QuotationStep2';

describe('repair-0812: valuesReconcile 归一到结果精度后再比', () => {
  it('TC-01（P0，负例，补齐 task-0806 TC-121）：仅第 10~12 位不同 → 判一致（本 BUG 现场值）', () => {
    expect(valuesReconcile('63.211125158028', '63.211125158')).toBe(true);
  });

  it('TC-02（P0，正例）：第 8 位差（1e-6 量级）→ 判不一致，阳性能力不丢', () => {
    expect(valuesReconcile('63.211125158', '63.211126158')).toBe(false);
  });

  it('TC-03（P0，边界·进位）：第 10 位为 5 四舍五入进位后两侧相等 → 判一致', () => {
    // 1.0000000005 归一到 9 位（HALF_UP）→ 1.000000001，与 b 归一后相等
    expect(valuesReconcile('1.0000000005', '1.000000001')).toBe(true);
  });

  it('TC-04（P0，边界·进位反向）：归一后仍不等 → 判不一致', () => {
    // 1.0000000005 归一到 9 位 → 1.000000001 ≠ 1.000000000
    expect(valuesReconcile('1.0000000005', '1.000000000')).toBe(false);
  });

  it('TC-05（P1，边界·尾零/等值异形）：formatFormulaResult 去尾零幂等 → 判一致', () => {
    expect(valuesReconcile('63.2111251580', '63.211125158')).toBe(true);
  });

  it('TC-06（P0，回归·空值/非数字分支，改动前后一致）', () => {
    // ① 两边都 null → true
    expect(valuesReconcile(null, null)).toBe(true);
    // ② 一边有值一边缺失 → false
    expect(valuesReconcile('1', null)).toBe(false);
    // ③ 非数字文本相等 → true
    expect(valuesReconcile('abc', 'abc')).toBe(true);
    // ④ 非数字文本不等 → false
    expect(valuesReconcile('abc', 'abd')).toBe(false);
  });

  it('TC-06b（P1，回归）：数组包裹（precisionValue 取 [0]）与 TC-01 同结果', () => {
    expect(valuesReconcile(['63.211125158028'], '63.211125158')).toBe(true);
  });
});
