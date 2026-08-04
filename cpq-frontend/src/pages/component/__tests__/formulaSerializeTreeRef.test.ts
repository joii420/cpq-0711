/**
 * formulaSerializeTreeRef.test.ts — task-0803 Task 8b：把父子取值接入真实的公式编辑入口。
 *
 * 覆盖范围：TabJoinFormulaDrawer 的文本表达式语言新增 6 个函数（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）
 * + 3 个树属性保留字（[层级]/[是否叶子]/[是否根]），照 KSUM/SUMIF 的现成先例接入
 * expressionToTokens（解析）/ tokensToDrawerExpression（反向序列化）。
 *
 * 语法要点（与主语言 [alias.field] 语法不同）：
 *   - PGET(<表达式>)/C*(<表达式>) 括号内字段名裸写，不加 []（父子取值永远指向宿主本页签字段，
 *     不需要跨页签消歧义）；[层级]/[是否叶子]/[是否根] 三个保留字例外，仍写在 [] 里。
 *   - 顶层（PGET/C* 之外）[层级] 等同样解析为 tree_attr，不局限于父子取值函数内部。
 *
 * 与 formulaSerialize.test.ts / formulaSerializeSumif.test.ts 用同一套 tabDefs 夹具约定
 * （componentId 用 cid-* 前缀，rowKeyFields/detailFields/allFields 三件套）。
 */
import { describe, it, expect } from 'vitest';
import {
  expressionToTokens,
  tokensToDrawerExpression,
  validateTreeRefWhitelist,
  containsTreeRef,
} from '../formulaSerialize';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import type { FormulaToken } from '../types';

// 宿主组件 BOM（self），字段里刻意放一个与树属性保留字同名的字段「层级」，
// 用于验证「保留字优先」裁决（即使字段真的叫「层级」，[层级] 仍解析成 tree_attr）。
const tabDefs: TabDef[] = [
  {
    alias: 'BOM',
    tabKey: 'cid-bom',
    componentId: 'cid-bom',
    componentName: 'BOM',
    rowKeyFields: ['料号'],
    detailFields: ['用量', '单价', '累计用量', '层级', '名称'],
    allFields: ['料号', '用量', '单价', '累计用量', '层级', '名称'],
    subtotalCols: [],
    self: true,
  },
  {
    alias: '来料',
    tabKey: 'cid-lai',
    componentId: 'cid-lai',
    componentName: '来料',
    rowKeyFields: ['料号'],
    detailFields: ['用量'],
    allFields: ['料号', '用量'],
    subtotalCols: [],
    self: false,
  },
];

const parse = (expr: string) => expressionToTokens(expr, tabDefs, ['料号'], 'cid-bom');
const serialize = (tokens: FormulaToken[]) => tokensToDrawerExpression(tokens, tabDefs, 'cid-bom');

describe('PGET(<表达式>) — dir=PARENT, agg=NONE', () => {
  it('解析 PGET(累计用量)', () => {
    const toks = parse('PGET(累计用量)');
    expect(toks).toHaveLength(1);
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'PARENT', agg: 'NONE' });
    const targetExpr = (toks[0] as any).targetExpr as FormulaToken[];
    expect(targetExpr).toEqual([{ type: 'field', value: '累计用量' }]);
  });
});

describe('C* 族 — dir=CHILD, agg 按函数区分', () => {
  it('CSUM(用量 * 单价) → agg=SUM，targetExpr 三个 token', () => {
    const toks = parse('CSUM(用量 * 单价)');
    expect(toks).toHaveLength(1);
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'CHILD', agg: 'SUM' });
    const targetExpr = (toks[0] as any).targetExpr as FormulaToken[];
    expect(targetExpr).toEqual([
      { type: 'field', value: '用量' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '单价' },
    ]);
  });

  it('CAVG(用量) → agg=AVG', () => {
    const toks = parse('CAVG(用量)');
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'CHILD', agg: 'AVG' });
  });

  it('CMAX(用量) → agg=MAX', () => {
    const toks = parse('CMAX(用量)');
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'CHILD', agg: 'MAX' });
  });

  it('CMIN(用量) → agg=MIN', () => {
    const toks = parse('CMIN(用量)');
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'CHILD', agg: 'MIN' });
  });

  it('CCOUNT(用量) → agg=COUNT', () => {
    const toks = parse('CCOUNT(用量)');
    expect(toks[0]).toMatchObject({ type: 'tree_ref', dir: 'CHILD', agg: 'COUNT' });
  });
});

describe('树属性保留字 [层级]/[是否叶子]/[是否根]', () => {
  it('[层级] → tree_attr LVL', () => {
    const toks = parse('[层级]');
    expect(toks).toEqual([{ type: 'tree_attr', attr: 'LVL' }]);
  });
  it('[是否叶子] → tree_attr IS_LEAF', () => {
    const toks = parse('[是否叶子]');
    expect(toks).toEqual([{ type: 'tree_attr', attr: 'IS_LEAF' }]);
  });
  it('[是否根] → tree_attr IS_ROOT', () => {
    const toks = parse('[是否根]');
    expect(toks).toEqual([{ type: 'tree_attr', attr: 'IS_ROOT' }]);
  });

  it('保留字优先：组件恰好有同名字段「层级」时，[层级] 仍解析为 tree_attr（不退化为 field）', () => {
    // tabDefs[0].detailFields 已含 '层级'（真实同名字段），验证不会被误判成 field
    expect(tabDefs[0].detailFields).toContain('层级');
    const toks = parse('[层级]');
    expect(toks).toEqual([{ type: 'tree_attr', attr: 'LVL' }]);
  });
});

describe('往返无损（token → 文本 → token 深相等）', () => {
  const cases: Array<{ label: string; token: FormulaToken }> = [
    {
      label: 'PGET',
      token: { type: 'tree_ref', dir: 'PARENT', agg: 'NONE', targetExpr: [{ type: 'field', value: '累计用量' }] },
    },
    {
      label: 'CSUM',
      token: {
        type: 'tree_ref', dir: 'CHILD', agg: 'SUM',
        targetExpr: [
          { type: 'field', value: '用量' },
          { type: 'operator', value: '*' },
          { type: 'field', value: '单价' },
        ],
      },
    },
    {
      label: 'CAVG',
      token: { type: 'tree_ref', dir: 'CHILD', agg: 'AVG', targetExpr: [{ type: 'field', value: '用量' }] },
    },
    {
      label: 'CMAX',
      token: { type: 'tree_ref', dir: 'CHILD', agg: 'MAX', targetExpr: [{ type: 'field', value: '用量' }] },
    },
    {
      label: 'CMIN',
      token: { type: 'tree_ref', dir: 'CHILD', agg: 'MIN', targetExpr: [{ type: 'field', value: '用量' }] },
    },
    {
      label: 'CCOUNT',
      token: { type: 'tree_ref', dir: 'CHILD', agg: 'COUNT', targetExpr: [{ type: 'field', value: '用量' }] },
    },
    { label: '[层级]', token: { type: 'tree_attr', attr: 'LVL' } },
    { label: '[是否叶子]', token: { type: 'tree_attr', attr: 'IS_LEAF' } },
    { label: '[是否根]', token: { type: 'tree_attr', attr: 'IS_ROOT' } },
  ];

  for (const { label, token } of cases) {
    it(`${label}: token → 文本 → token 深相等`, () => {
      const text = serialize([token]);
      const roundTripped = parse(text);
      expect(roundTripped).toHaveLength(1);
      expect(roundTripped[0]).toEqual(token);
    });
  }

  it('反向序列化文本形态：PGET(累计用量) / CSUM(用量 * 单价) / [层级]（逐字比对定稿输出）', () => {
    expect(serialize([cases[0].token])).toBe('PGET(累计用量)');
    expect(serialize([cases[1].token])).toBe('CSUM(用量 * 单价)');
    expect(serialize([cases[6].token])).toBe('[层级]');
  });
});

describe('F-7 嵌套非法：CSUM(...) 内层跨页签引用', () => {
  it('CSUM([来料.用量]) 能正常解析成 tree_ref，但 validateTreeRefWhitelist 拒绝其内层 cross_tab_ref', () => {
    const toks = parse('CSUM([来料.用量])');
    expect(toks).toHaveLength(1);
    expect(toks[0].type).toBe('tree_ref');
    const targetExpr = (toks[0] as any).targetExpr as FormulaToken[];
    expect(targetExpr.some((t) => t.type === 'cross_tab_ref')).toBe(true);

    const result = validateTreeRefWhitelist(toks);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('cross_tab_ref');
  });
});

describe('containsTreeRef（F-2 用）', () => {
  it('含 PGET/C* 的 tokens → true', () => {
    expect(containsTreeRef(parse('PGET(累计用量)'))).toBe(true);
    expect(containsTreeRef(parse('CSUM(用量)'))).toBe(true);
  });
  it('不含 PGET/C* 的 tokens → false（含裸 [层级] 树属性，不算 tree_ref）', () => {
    expect(containsTreeRef(parse('[层级]'))).toBe(false);
    expect(containsTreeRef(parse('[用量] + [单价]'))).toBe(false);
  });
  it('嵌套在 CSUM(...) 内部的普通算式不产生额外 tree_ref；组合公式仍能命中顶层 tree_ref', () => {
    const toks = parse('[用量] + CSUM(单价)');
    expect(containsTreeRef(toks)).toBe(true);
  });
  it('null/undefined/空数组 → false', () => {
    expect(containsTreeRef(null)).toBe(false);
    expect(containsTreeRef(undefined)).toBe(false);
    expect(containsTreeRef([])).toBe(false);
  });
});
