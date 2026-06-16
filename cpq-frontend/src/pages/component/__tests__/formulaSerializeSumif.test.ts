import { describe, it, expect } from 'vitest';
import { expressionToTokens, tokensToDrawerExpression } from '../formulaSerialize';
import type { TabDef } from '../../../services/tabJoinFormulaService';

// 两个页签：其他费用(源, componentId=cid-fee)、来料(宿主自身, componentId=cid-host)
const tabDefs: TabDef[] = [
  {
    alias: '来料',
    tabKey: 'cid-host',
    componentId: 'cid-host',
    componentName: '来料',
    rowKeyFields: ['料件'],
    detailFields: ['数量', '金额'],
    allFields: ['料件', '数量', '金额'],
    subtotalCols: [],
    self: true,
  },
  {
    alias: '其他费用',
    tabKey: 'cid-fee',
    componentId: 'cid-fee',
    componentName: '其他费用',
    rowKeyFields: ['项次'],
    detailFields: ['费用', '比例'],
    allFields: ['项次', '类型', '费用', '比例'],
    subtotalCols: [],
    self: false,
  },
];

describe('SUMIF in formulaSerialize', () => {
  it('parses SUMIF text → cross_tab_ref token with predicate + targetExpr', () => {
    const toks = expressionToTokens(
      "SUMIF([其他费用.类型]='管理费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      'cid-host',
    );
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect(ct).toBeTruthy();
    expect((ct as any).agg).toBe('SUM');
    expect((ct as any).predicate).toEqual({
      op: '=',
      lhs: { kind: 'sourceField', field: '类型' },
      rhs: { kind: 'literal', value: '管理费' },
    });
    expect((ct as any).source).toBe('cid-fee');
  });

  it('round-trips SUMIF combined with an operator', () => {
    // [来料.数量] 中 来料 是 self 页签，expressionToTokens 产出裸 field token（无别名）
    // 序列化时回显为 [数量]；故 round-trip expr 应使用 [数量] 或从非 self 角度验证
    // 此处验证：SUMIF 部分正确 round-trip，宿主列引用符合现有 self-field 语义
    const parseExpr = "[来料.数量] * SUMIF([其他费用.类型] = '管理费', [其他费用.费用])";
    const toks = expressionToTokens(parseExpr, tabDefs, ['料件'], 'cid-host');
    const back = tokensToDrawerExpression(toks, tabDefs);
    // 宿主列回显为裸 [数量]；SUMIF 部分正确还原
    const expectedBack = "[数量] * SUMIF([其他费用.类型] = '管理费', [其他费用.费用])";
    expect(back.replace(/\s+/g, ' ').trim()).toBe(expectedBack.replace(/\s+/g, ' ').trim());
  });

  it('COUNTIF single-arg parses with agg COUNT and no targetExpr', () => {
    const toks = expressionToTokens(
      "COUNTIF([其他费用.类型]='管理费')",
      tabDefs,
      ['料件'],
      'cid-host',
    );
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect((ct as any).agg).toBe('COUNT');
    expect((ct as any).predicate).toBeTruthy();
    expect((ct as any).targetExpr).toBeUndefined();
  });

  it('AVGIF parses correctly with agg AVG', () => {
    const toks = expressionToTokens(
      "AVGIF([其他费用.类型]='运费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      'cid-host',
    );
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect((ct as any).agg).toBe('AVG');
    expect((ct as any).predicate).toBeTruthy();
    expect((ct as any).source).toBe('cid-fee');
  });

  it('SUMIF case-insensitive (sumif lowercase)', () => {
    const toks = expressionToTokens(
      "sumif([其他费用.类型]='管理费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      'cid-host',
    );
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect((ct as any).agg).toBe('SUM');
    expect((ct as any).predicate).toBeTruthy();
  });

  it('SUMIF standalone round-trips', () => {
    // serializePredicate 输出 op 两侧带空格（"[x] = 'y'"），测试 expr 需与之对齐
    const expr = "SUMIF([其他费用.类型] = '管理费', [其他费用.费用])";
    const toks = expressionToTokens(expr, tabDefs, ['料件'], 'cid-host');
    const back = tokensToDrawerExpression(toks, tabDefs);
    expect(back.replace(/\s+/g, ' ').trim()).toBe(expr.replace(/\s+/g, ' ').trim());
  });

  it('COUNTIF round-trips (no valueExpr)', () => {
    const expr = "COUNTIF([其他费用.类型] = '管理费')";
    const toks = expressionToTokens(expr, tabDefs, ['料件'], 'cid-host');
    const back = tokensToDrawerExpression(toks, tabDefs);
    expect(back.replace(/\s+/g, ' ').trim()).toBe(expr.replace(/\s+/g, ' ').trim());
  });

  it('non-SUMIF SUM still works (predicate=undefined path unchanged)', () => {
    // 验证现有 SUM([别名.字段]) 路径零回归
    const expr = 'SUM([其他费用.费用])';
    const toks = expressionToTokens(expr, tabDefs, ['料件'], 'cid-host');
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect((ct as any).predicate).toBeFalsy();
    const back = tokensToDrawerExpression(toks, tabDefs);
    expect(back.trim()).toBe(expr.trim());
  });
});
