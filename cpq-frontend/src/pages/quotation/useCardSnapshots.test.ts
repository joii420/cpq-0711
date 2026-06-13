/**
 * useCardSnapshots.test.ts
 *
 * TDD：computeRowKey 字段感知版（新签名）回归。
 * 对齐后端 FormulaCalculator.computeRowKey(rowKeyFields, fields, driverRow, basicDataValues)。
 *
 * 核心 Bug：外购件 row_key_fields=["料件","要素"]（字段名），driverRow 键是视图列别名
 * （"_料件"/"_要素"），直接 driverRow["料件"] → undefined → 4 行 rowKey 全塌成 "||"
 * → cross_tab SUM 退化为末值×4 错误。
 *
 * 修复：resolveRowKeyPart 按字段 defaultSource 解析，BNF_PATH/BASIC_DATA → bnfDriverLookupKey(path)
 * 在 basicDataValues 里取，GLOBAL_VARIABLE → @gvar:code；全空 → 行号回退。
 */

import { describe, it, expect } from 'vitest';
import { computeRowKey } from './useCardSnapshots';
import { bnfDriverLookupKey } from './useDriverExpansions';

// ─── Fixture 构造器（外购件场景）────────────────────────────────────────────
// 外购件 rowKeyFields = ["料件", "要素"]（字段名，非视图列别名）
// driverRow 键 = 视图别名 _料件 / _要素 / _费用
// basicDataValues 键 = {$wgj_view._料件} / {$wgj_view._要素}

const RKF = ['料件', '要素'];

/** 单行 fixture：driverRow 用 _前缀别名，basicDataValues 用 bnfDriverLookupKey */
function makeRow(liao: string, yaoSu: string) {
  const driverRow: Record<string, any> = {
    _料件: liao,
    _要素: yaoSu,
    _费用: 0.05,
  };
  const basicDataValues: Record<string, any> = {
    [bnfDriverLookupKey('$wgj_view._料件')]: liao,
    [bnfDriverLookupKey('$wgj_view._要素')]: yaoSu,
  };
  return { driverRow, basicDataValues };
}

/** 外购件字段定义：料件(INPUT_TEXT, defaultSource.BASIC_DATA) + 要素(INPUT_TEXT, defaultSource.BASIC_DATA) */
const FIELDS = [
  {
    name: '料件',
    fieldType: 'INPUT_TEXT',
    defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._料件' },
  },
  {
    name: '要素',
    fieldType: 'INPUT_TEXT',
    defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._要素' },
  },
  {
    name: '费用',
    fieldType: 'INPUT_NUMBER',
    defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._费用' },
  },
];

// ─── 外购件 4 行数据（计划 T8 对拍夹具）──────────────────────────────────
const ROW0 = makeRow('料9', '加工费');   // 费用 0.05
const ROW1 = makeRow('料9', '单价');     // 费用 0.20
const ROW2 = makeRow('料9', '运费');     // 费用 0.002
const ROW3 = makeRow('料9', '包装费');   // 费用 0.007

// ─── Tests ────────────────────────────────────────────────────────────────

describe('computeRowKey — 字段感知版（新签名）', () => {
  // ── T5 基础断言 ─────────────────────────────────────────────────────────

  it('rowKeyFields 为空 → 行号', () => {
    const { driverRow, basicDataValues } = makeRow('料9', '加工费');
    expect(computeRowKey(FIELDS, [], driverRow, 0, basicDataValues)).toBe('0');
    expect(computeRowKey(FIELDS, null, driverRow, 3, basicDataValues)).toBe('3');
  });

  it('哨兵 __seq_no__ → 行号', () => {
    const { driverRow, basicDataValues } = makeRow('料9', '加工费');
    expect(computeRowKey(FIELDS, ['__seq_no__'], driverRow, 2, basicDataValues)).toBe('2');
  });

  it('外购件：driverRow 用 _前缀别名，BDV 有 {$wgj_view._料件} → 解析字段名键 "料9||加工费"', () => {
    const { driverRow, basicDataValues } = ROW0;
    const rk = computeRowKey(FIELDS, RKF, driverRow, 0, basicDataValues);
    // 修复前：driverRow["料件"]=undefined, driverRow["要素"]=undefined → joined="||" → 全空 → "0"
    // 修复后：解析 BDV → "料9||加工费"
    expect(rk).toBe('料9||加工费');
  });

  it('旧场景：driverRow 键 == 字段名（直读）→ 兼容', () => {
    // 无 defaultSource 字段，driverRow 键直接是字段名
    const fields = [
      { name: 'material_no', fieldType: 'INPUT_TEXT' },
      { name: 'seq', fieldType: 'INPUT_TEXT' },
    ];
    const driverRow = { material_no: 'M001', seq: 'A' };
    const rk = computeRowKey(fields, ['material_no', 'seq'], driverRow, 0, {});
    expect(rk).toBe('M001||A');
  });

  it('全空（driverRow 无直读，BDV 也无对应键）→ 行号回退', () => {
    const driverRow = { _other: 'x' };
    const basicDataValues = {};
    const rk = computeRowKey(FIELDS, RKF, driverRow, 5, basicDataValues);
    expect(rk).toBe('5');
  });

  it('GLOBAL_VARIABLE defaultSource → @gvar:CODE 键查 BDV', () => {
    const fields = [
      {
        name: '汇率',
        fieldType: 'INPUT_NUMBER',
        defaultSource: { type: 'GLOBAL_VARIABLE', code: 'EXCHANGE_RATE' },
      },
    ];
    const basicDataValues: Record<string, any> = { '@gvar:EXCHANGE_RATE': 6.9 };
    const rk = computeRowKey(fields, ['汇率'], {}, 0, basicDataValues);
    expect(rk).toBe('6.9');
  });

  it('BNF_PATH defaultSource → bnfDriverLookupKey(path) 查 BDV', () => {
    const fields = [
      {
        name: '料件',
        fieldType: 'INPUT_TEXT',
        defaultSource: { type: 'BNF_PATH', path: '$ys_view._料件' },
      },
    ];
    const bdvKey = bnfDriverLookupKey('$ys_view._料件');
    const basicDataValues = { [bdvKey]: '料8' };
    const rk = computeRowKey(fields, ['料件'], {}, 0, basicDataValues);
    expect(rk).toBe('料8');
  });

  // ── T8 对拍：4 行 rowKey 互不相同（cross_tab 行区分护栏）────────────────

  it('T8: 外购件 4 行 rowKey 互不相同', () => {
    const rk0 = computeRowKey(FIELDS, RKF, ROW0.driverRow, 0, ROW0.basicDataValues);
    const rk1 = computeRowKey(FIELDS, RKF, ROW1.driverRow, 1, ROW1.basicDataValues);
    const rk2 = computeRowKey(FIELDS, RKF, ROW2.driverRow, 2, ROW2.basicDataValues);
    const rk3 = computeRowKey(FIELDS, RKF, ROW3.driverRow, 3, ROW3.basicDataValues);

    // 4 个 rowKey 互不相同
    const keys = [rk0, rk1, rk2, rk3];
    expect(new Set(keys).size).toBe(4);

    // 逐字节与后端对拍（分隔符 "||"）
    expect(rk0).toBe('料9||加工费');
    expect(rk1).toBe('料9||单价');
    expect(rk2).toBe('料9||运费');
    expect(rk3).toBe('料9||包装费');
  });

  it('T8: 与后端 computeRowKey 逐字节对齐（分隔符 "||"，全空 → 行号字符串）', () => {
    // 全空行 → 行号（后端 null → 调用方按 idx 兜底 = 行号字符串）
    const rkEmpty = computeRowKey(FIELDS, RKF, {}, 7, {});
    expect(rkEmpty).toBe('7');

    // 单字段部分空（只有料件，要素缺失）
    const fields1 = [FIELDS[0]];
    const { driverRow, basicDataValues } = ROW0;
    const rk = computeRowKey(fields1, ['料件'], driverRow, 0, basicDataValues);
    expect(rk).toBe('料9');
  });
});
