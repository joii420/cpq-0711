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
 *
 * ⚠️ 测试工程师执行期核实（2026-08-13）：上述 test.md 路径在本 worktree /
 * 本仓库全部 git 历史（含 stash）中均不存在 —— `git log --all` 无任何提交曾在
 * dev-docs 下新增/修改过 repair-0812 相关文件，`dev-docs/INDEX.md` 也无该任务
 * 登记。以下 TC-00/00b/01b/02b/05b/05c/05d/06c 是根据任务简报里对这些用例的
 * 逐条数值级描述、结合本文件已实现的 TC-01~TC-06b 命名规律、并对 precision.ts
 * / formulaEngine.ts 真实实现验证后补齐的，具体数值均已实测通过，但**并非誊抄
 * 自一份真实存在的已评审 test.md**——详见 test-report.md「文档缺口」章节。
 */
import { describe, it, expect } from 'vitest';
import { valuesReconcile } from './QuotationStep2';
import { formatFormulaResult } from '../../utils/precision';
import { isWithinTolerance } from '../../utils/formulaEngine';

describe('repair-0812: valuesReconcile 归一到结果精度后再比', () => {
  it('TC-00（P0，独立断言）：formatFormulaResult 对已知 12 位漂移值舍入正确且幂等 —— 排除"两侧被同时错误移动到同一个值"掩盖 TC-01', () => {
    const rounded = formatFormulaResult('63.211125158028');
    expect(rounded).toBe('63.211125158');
    // 幂等：对已归一值再归一一次必须不变
    expect(formatFormulaResult(rounded)).toBe(rounded);
  });

  it('TC-00b（P0，独立断言，双侧）：isWithinTolerance 默认容差 1e-12 上下边界均生效，防止容差被静默放宽', () => {
    // 差 5e-13 < 1e-12 → true
    expect(isWithinTolerance('1', '1.0000000000005')).toBe(true);
    // 差 2e-12 > 1e-12 → false（若容差被放宽到 1e-9 这条会翻转为 true，是唯一能抓到"放宽"的断言）
    expect(isWithinTolerance('1', '1.000000000002')).toBe(false);
  });

  it('TC-01（P0，负例，补齐 task-0806 TC-121）：仅第 10~12 位不同 → 判一致（本 BUG 现场值）', () => {
    expect(valuesReconcile('63.211125158028', '63.211125158')).toBe(true);
  });

  it('TC-01b（P0，负例，第二组真实现场值·材料价格）：与 TC-01 同规律的独立数据点，排除 TC-01 是唯一巧合', () => {
    // QT-20260811-0169 lineItem b7077e71 tabs[2].resolvedRows[4].材料价格 的 12 位遗留值
    expect(valuesReconcile('127.342371193912', '127.342371194')).toBe(true);
  });

  it('TC-02（P0，正例）：第 6 位差（1e-6 量级）→ 判不一致，阳性能力不丢', () => {
    expect(valuesReconcile('63.211125158', '63.211126158')).toBe(false);
  });

  it('TC-02b（P0，正例，边界·第 9 位差）：唯一能卡住"FORMULA_RESULT_SCALE 写错一位"的用例', () => {
    // 若 scale 误写成 8，两侧归一后都会塌缩成 '1.00000000' 而被误判一致；
    // 正确 scale=9 时归一后原样保留，差 1e-9 > 容差 1e-12 → 判不一致
    expect(valuesReconcile('1.000000001', '1.000000002')).toBe(false);
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

  it('TC-05b（P2，边界·超长尾零）：小数部分全 0 且远超 9 位 → 归一到整数同值 → 判一致', () => {
    expect(valuesReconcile('1.000000000000000000', '1')).toBe(true);
  });

  it('TC-05c（P2，边界·整数形态无小数点）：纯整数字符串与等值小数字符串 → 判一致', () => {
    expect(valuesReconcile('100', '100.000000000')).toBe(true);
  });

  it('TC-05d（P2，边界·负零）：负零归一后与正零同值 → 判一致', () => {
    expect(valuesReconcile('-0.000000000', '0')).toBe(true);
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

  it('TC-06c（P1，回归·空值异形组合）：一边 ""、一边 null（均属"缺失"但字面不同）→ 判一致', () => {
    expect(valuesReconcile('', null)).toBe(true);
  });
});
