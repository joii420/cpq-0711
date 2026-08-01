/**
 * 端到端闭环：列小计「怎么算出来 + 以什么键登记 + 被 SUM 内引用时取到的是不是它」。
 *
 * 与 formulaSerialize.test.ts 的 E2 用例互补——那组只验 token 结构，
 * FormulaCalculatorCrossTabTest 那条只验「给定键值能算对」（值是手工 put 的）。
 * 本文件补掉中间这段：**列小计的真实计算与键登记**，避免出现
 * 「序列化对了、求值也对了，但键对不上 → 取值静默为 0」的断链。
 *
 * fixture 取自 cpq_db_0724 真实配置：
 *   产品 COMP-0135 行键[销售料号]，税率 is_subtotal=true
 *   物料 COMP-0032 行键[料件]（宿主），与产品互不包含
 *   材料成本 COMP-0133 行键含[料件]，与宿主可比
 */
import { describe, it, expect } from 'vitest';
import { getComponentSubtotals } from './QuotationStep2';
import { expressionToTokens } from '../component/formulaSerialize';
import { evaluateExpression } from '../../utils/formulaEngine';
import type { TabDef } from '../../services/tabJoinFormulaService';

// ── 产品页签：税率 is_subtotal=true，单行 1.13 ──
const componentData = [
  {
    componentId: 'cid-prod',
    componentCode: 'COMP-0135',
    tabName: '产品',
    componentType: 'NORMAL',
    fields: [
      { name: '销售料号', field_type: 'INPUT_TEXT' },
      { name: '税率', field_type: 'INPUT_NUMBER', is_subtotal: true },
    ],
    formulas: [],
    rows: [{ 销售料号: 'S-001', 税率: 1.13 }],
    componentData: [],
    snapshotRows: 1,
    subtotal: 0,
  },
] as any;

const lineItem = { id: 'li-1', productPartNo: 'S-001', componentData } as any;

// ── 公式侧 tabDefs ──
const hostWL: TabDef = {
  alias: 'COMP-0032', tabKey: 't-wl', componentId: 'cid-wl', componentName: '物料',
  rowKeyFields: ['料件'], detailFields: ['材料净重'], subtotalCols: [],
};
const mc: TabDef = {
  alias: 'COMP-0133', tabKey: 't-mc', componentId: 'cid-mc', componentName: '材料成本',
  rowKeyFields: ['销售料号', '元素', '料件'], detailFields: ['元素单价'], subtotalCols: [],
};
const prod: TabDef = {
  alias: 'COMP-0135', tabKey: 't-prod', componentId: 'cid-prod', componentName: '产品',
  rowKeyFields: ['销售料号'], detailFields: ['销售料号'], subtotalCols: ['税率'],
};
const defs = [hostWL, mc, prod];

describe('列小计端到端：计算 → 键登记 → SUM 内引用取值', () => {
  it('① 列小计真的被算出来，且三种键（componentId / componentCode / tabName）都登记', () => {
    const subs = getComponentSubtotals(lineItem);
    // 键格式必须与 formulaSerialize 产出的 component_code 对齐
    expect(subs['COMP-0135#税率']).toBeCloseTo(1.13, 4);
    expect(subs['cid-prod#税率']).toBeCloseTo(1.13, 4);
    expect(subs['产品#税率']).toBeCloseTo(1.13, 4);
  });

  it('② 序列化产出的 component_code 必须命中已登记的键（键口径对齐护栏）', () => {
    const subs = getComponentSubtotals(lineItem);
    const tokens = expressionToTokens(
      'SUM([材料成本.元素单价] * [产品.税率])', defs, ['料件'], 'cid-wl',
    );
    const csToken: any = (tokens[0] as any).targetExpr.find(
      (t: any) => t.type === 'component_subtotal',
    );
    expect(csToken).toBeDefined();
    // 求值端优先查 `${component_code}#${value}` —— 这个键必须真实存在于 subs 中
    const lookupKey = `${csToken.component_code}#${csToken.value}`;
    expect(lookupKey).toBe('COMP-0135#税率');
    expect(subs[lookupKey]).toBeCloseTo(1.13, 4);
  });

  it('③ 端到端求值：SUM 内每行 × 列小计，取到的是 1.13 而非 0', () => {
    const subs = getComponentSubtotals(lineItem);
    const tokens = expressionToTokens(
      'SUM([材料成本.元素单价] * [产品.税率])', defs, ['料件'], 'cid-wl',
    );
    const crossTabRows = {
      'cid-mc': [
        { 料件: 'P1', 元素单价: 2 },
        { 料件: 'P1', 元素单价: 4 },
      ],
    };
    const v = evaluateExpression(
      tokens as any, {}, subs, undefined, undefined, undefined, 'S-001',
      undefined, undefined, undefined, { 料件: 'P1' }, crossTabRows,
    );
    // (2 × 1.13) + (4 × 1.13) = 6.78；若键对不上则取 0 → 结果 0
    expect(v).toBeCloseTo(6.78, 4);
  });

  it('④ 反向护栏：键对不上时结果塌成 0（证明上面的 6.78 确实来自列小计而非巧合）', () => {
    const tokens = expressionToTokens(
      'SUM([材料成本.元素单价] * [产品.税率])', defs, ['料件'], 'cid-wl',
    );
    const crossTabRows = {
      'cid-mc': [
        { 料件: 'P1', 元素单价: 2 },
        { 料件: 'P1', 元素单价: 4 },
      ],
    };
    // 故意喂一个键名不匹配的小计表
    const v = evaluateExpression(
      tokens as any, {}, { '错误键#税率': 1.13 }, undefined, undefined, undefined, 'S-001',
      undefined, undefined, undefined, { 料件: 'P1' }, crossTabRows,
    );
    expect(v).toBeCloseTo(0, 6);
  });

  it('⑤ 多行时列小计是求和 —— 税率标 is_subtotal 的语义后果（配置警示）', () => {
    const twoRow = {
      ...lineItem,
      componentData: [{ ...componentData[0], rows: [{ 税率: 1.13 }, { 税率: 1.13 }], snapshotRows: 2 }],
    } as any;
    const subs = getComponentSubtotals(twoRow);
    // is_subtotal 列的语义就是「整列求和」：两行 1.13 → 2.26，不是 1.13
    expect(subs['COMP-0135#税率']).toBeCloseTo(2.26, 4);
  });
});
