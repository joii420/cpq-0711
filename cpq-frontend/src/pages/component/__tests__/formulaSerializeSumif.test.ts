import { describe, it, expect } from 'vitest';
import { expressionToTokens, tokensToDrawerExpression, parseFormulaSegments } from '../formulaSerialize';
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
    detailFields: ['类型', '费用', '比例'],
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

// ─── Phase 3 Task 3: parseFormulaSegments 着色测试 ─────────────────────────────

describe('parseFormulaSegments SUMIF 着色', () => {
  it('SUMIF 函数名 segment 不应被标红（应识别为函数）', () => {
    const segs = parseFormulaSegments(
      "SUMIF([其他费用.类型]='管理费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      true,
    );
    // 找到含 SUMIF 的文本 segment
    const sumifSeg = segs.find((s: any) => s.text !== undefined
      ? s.text.toUpperCase().includes('SUMIF')
      : s.display?.toUpperCase().includes('SUMIF') || s.raw?.toUpperCase().includes('SUMIF'));
    expect(sumifSeg).toBeTruthy();
    // SUMIF 所在 segment 不能是 error 颜色（红色 = 未知/非法）
    expect((sumifSeg as any).color).not.toBe('error');
    expect((sumifSeg as any).color).not.toBe('red');
  });

  it('SUMIF 内部的 [引用] 应被正确着色（不标红）', () => {
    const segs = parseFormulaSegments(
      "SUMIF([其他费用.类型]='管理费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      true,
    );
    // SUMIF 内部有两个 [其他费用.xxx] 引用，它们是跨页签引用应着绿色（非 source-only 时 red；insideFn=true 放开聚合约束）
    const blockSegs = segs.filter((s: any) => s.isBlock);
    // 在 insideFn=true 下，跨页签细节引用被允许 → color 应为 green 或 blue 或 yellow（不为 red）
    for (const seg of blockSegs) {
      expect((seg as any).color).not.toBe('red');
    }
  });

  it('COUNTIF 不应被标红', () => {
    const segs = parseFormulaSegments(
      "COUNTIF([其他费用.类型]='管理费')",
      tabDefs,
      ['料件'],
      true,
    );
    const countifSeg = segs.find((s: any) =>
      (s.raw ?? '').toUpperCase().includes('COUNTIF') ||
      (s.display ?? '').toUpperCase().includes('COUNTIF'),
    );
    expect(countifSeg).toBeTruthy();
    expect((countifSeg as any).color).not.toBe('red');
    expect((countifSeg as any).color).not.toBe('error');
  });

  it('AVGIF / MINIF / MAXIF 均不标红', () => {
    for (const fn of ['AVGIF', 'MINIF', 'MAXIF']) {
      const segs = parseFormulaSegments(
        `${fn}([其他费用.类型]='管理费', [其他费用.费用])`,
        tabDefs,
        ['料件'],
        true,
      );
      const fnSeg = segs.find((s: any) =>
        (s.raw ?? '').toUpperCase().includes(fn) ||
        (s.display ?? '').toUpperCase().includes(fn),
      );
      expect(fnSeg, `${fn} segment 应存在`).toBeTruthy();
      expect((fnSeg as any).color, `${fn} 不应标红`).not.toBe('red');
      expect((fnSeg as any).color, `${fn} 不应标 error`).not.toBe('error');
    }
  });

  it('SUMIF 与运算符组合：两者均不标红', () => {
    const segs = parseFormulaSegments(
      "[来料.数量] * SUMIF([其他费用.类型]='管理费', [其他费用.费用])",
      tabDefs,
      ['料件'],
      true,
    );
    const sumifSeg = segs.find((s: any) =>
      (s.raw ?? '').toUpperCase().includes('SUMIF'),
    );
    expect(sumifSeg).toBeTruthy();
    expect((sumifSeg as any).color).not.toBe('red');
    expect((sumifSeg as any).color).not.toBe('error');
  });

  it('普通 SUM 着色零回归：FN_NAMES 扩展不影响 SUM', () => {
    const segs = parseFormulaSegments(
      'SUM([其他费用.费用])',
      tabDefs,
      ['料件'],
      true,
    );
    // SUM 开头的文本 segment 应存在，且不标红
    const sumSeg = segs.find((s: any) => (s.raw ?? '').toUpperCase().startsWith('SUM('));
    expect(sumSeg).toBeTruthy();
    expect((sumSeg as any).color).not.toBe('red');
  });
});
