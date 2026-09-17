/**
 * repair-260916 — 「(小计)」后缀写法（问题说明 5.1）：解析 / 报错 / 回显往返 / 着色。
 * tabDefs 形状照 GET /components/{id}/tab-defs 的实际字段（勾了小计的列同时在 detailFields 与 subtotalCols）。
 * 页签与字段取自 问题说明 6.1 的 RP0916 测试组件。
 */
import { describe, it, expect } from 'vitest';
import {
  expressionToTokens,
  tokensToDrawerExpression,
  classifyRefSegment,
  parseFormulaSegments,
  checkMappable,
  type TabDef,
} from './formulaSerialize';
import type { FormulaToken } from './types';

const HOST: TabDef = {
  alias: 'COMP-9001', tabKey: 'cid-host', componentId: 'cid-host', componentName: 'RP0916宿主',
  componentType: 'NORMAL', self: true,
  rowKeyFields: ['销售料号', '料号'], detailFields: ['数量', '成本'],
  allFields: ['销售料号', '料号', '数量', '成本'], subtotalCols: ['数量'],
};
const FEE: TabDef = {
  alias: 'COMP-9002', tabKey: 'cid-fee', componentId: 'cid-fee', componentName: 'RP0916加工费',
  componentType: 'NORMAL',
  rowKeyFields: ['销售料号', '料号'], detailFields: ['加工费', '备注数'],
  allFields: ['销售料号', '料号', '加工费', '备注数'], subtotalCols: ['加工费'],
};
const PROC: TabDef = {
  alias: 'COMP-9003', tabKey: 'cid-proc', componentId: 'cid-proc', componentName: 'RP0916工序',
  componentType: 'NORMAL',
  rowKeyFields: ['工序'], detailFields: ['工时'], allFields: ['工序', '工时'], subtotalCols: [],
};
const DEFS = [HOST, FEE, PROC];
const RKF = ['销售料号', '料号'];
const SELF = 'cid-host';
const MATCH = [{ a: '销售料号', b: '销售料号' }, { a: '料号', b: '料号' }];

const parse = (expr: string) => expressionToTokens(expr, DEFS, RKF, SELF);
const echo = (tokens: FormulaToken[]) =>
  tokensToDrawerExpression(tokens, DEFS, SELF).replace(/\s+/g, ' ').trim();

describe('F-1 顶层：[页签.列] 一律本行取值，[页签.列(小计)] 才是整列小计', () => {
  it('AC-1 勾了小计的列，不带后缀 → cross_tab_ref NONE + 行键交集 match', () => {
    const t = parse('[RP0916加工费.加工费]');
    expect(t).toEqual([{
      type: 'cross_tab_ref', source: 'cid-fee', sourceLabel: 'RP0916加工费',
      target: '加工费', agg: 'NONE', match: MATCH,
    }]);
  });

  it('AC-1 与引用未勾小计的列同构（只差 target）', () => {
    const a = parse('[RP0916加工费.加工费]')[0];
    const b = parse('[RP0916加工费.备注数]')[0];
    expect({ ...a, target: 'X' }).toEqual({ ...b, target: 'X' });
  });

  it('AC-2 [页签.列(小计)] → component_subtotal，编号作 tab_name/component_code，无 is_tab_total', () => {
    const t = parse('[RP0916加工费.加工费(小计)]');
    expect(t).toEqual([{
      type: 'component_subtotal', value: '加工费',
      tab_name: 'COMP-9002', component_code: 'COMP-9002', label: 'RP0916加工费·加工费',
    }]);
    expect('is_tab_total' in t[0]).toBe(false);
  });

  it('编号引用串同样可用：[COMP-9002.加工费(小计)]', () => {
    expect(parse('[COMP-9002.加工费(小计)]')).toEqual(parse('[RP0916加工费.加工费(小计)]'));
  });

  it('AC-3① 四种写法混用 → NONE / 小计 / 页签合计 / SUM', () => {
    const t = parse('[RP0916加工费.加工费] + [RP0916加工费.加工费(小计)] + [RP0916加工费(总计)] + SUM([RP0916加工费.加工费])');
    const refs = t.filter((x) => x.type !== 'operator');
    expect(refs.map((x) => [x.type, x.agg ?? null, x.is_tab_total ?? null])).toEqual([
      ['cross_tab_ref', 'NONE', null],
      ['component_subtotal', null, null],
      ['component_subtotal', null, true],
      ['cross_tab_ref', 'SUM', null],
    ]);
    expect(checkMappable(t).mappable).toBe(true);
  });

  it('本页签的列（不带后缀）仍取同行值 field（行为不变）', () => {
    expect(parse('[RP0916宿主.数量]')).toEqual([{ type: 'field', value: '数量' }]);
  });
});

describe('F-1 报错文案（问题说明 5.1，逐字）', () => {
  it('AC-5a 未勾小计的列写 (小计)', () => {
    expect(() => parse('[RP0916加工费.备注数(小计)]'))
      .toThrow(new Error('页签「RP0916加工费」的列「备注数」没有勾选小计，不能写成「(小计)」'));
  });
  it('AC-5a 不存在的列写 (小计) 同一文案', () => {
    expect(() => parse('[RP0916加工费.不存在(小计)]'))
      .toThrow(new Error('页签「RP0916加工费」的列「不存在」没有勾选小计，不能写成「(小计)」'));
  });
  it('AC-5b 本页签自身的小计（即使该列勾了小计）', () => {
    expect(() => parse('[RP0916宿主.数量(小计)]'))
      .toThrow(new Error('不能引用本页签自身的小计：[RP0916宿主.数量(小计)]'));
  });
  it('AC-5c 没写列名', () => {
    expect(() => parse('[RP0916加工费(小计)]'))
      .toThrow(new Error('「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]'));
  });
  it('没写列名优先于未知页签（判定顺序第一条）', () => {
    expect(() => parse('[不存在的页签(小计)]'))
      .toThrow(new Error('「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]'));
  });
  it('未知页签沿用现有报错', () => {
    expect(() => parse('[不存在的页签.加工费(小计)]')).toThrow(/未知页签 "不存在的页签"/);
  });
  it('AC-6a SUM 单列写法里的 (小计)', () => {
    expect(() => parse('SUM([RP0916加工费.加工费(小计)])'))
      .toThrow(new Error('SUM() 里不能再对小计求和：[RP0916加工费.加工费(小计)] 已是整列小计'));
  });
  it('AVG 单列写法同样拒绝，函数名取实际大写名', () => {
    expect(() => parse('avg([RP0916加工费.加工费(小计)])'))
      .toThrow(new Error('AVG() 里不能再对小计求和：[RP0916加工费.加工费(小计)] 已是整列小计'));
  });
  it('AC-6c KSUM 内层', () => {
    expect(() => parse('SUM(KSUM([RP0916加工费.加工费(小计)]))'))
      .toThrow(new Error('KSUM() 内不支持 (小计) 小计引用 [RP0916加工费.加工费(小计)]，请引用明细字段或把小计放到外层'));
  });
  it('AC-6d SUMIF 取值', () => {
    expect(() => parse('SUMIF([RP0916加工费.备注数] > 0, [RP0916加工费.加工费(小计)])'))
      .toThrow(new Error('SUMIF() 里不支持「(小计)」引用 [RP0916加工费.加工费(小计)]'));
  });
  it('SUMIF 条件里的 (小计) 同样拒绝', () => {
    expect(() => parse('COUNTIF([RP0916加工费.加工费(小计)] > 0)'))
      .toThrow(new Error('COUNTIF() 里不支持「(小计)」引用 [RP0916加工费.加工费(小计)]'));
  });
  it('判定顺序：本页签优先于所在函数不允许', () => {
    expect(() => parse('SUM([RP0916宿主.数量(小计)])'))
      .toThrow(new Error('不能引用本页签自身的小计：[RP0916宿主.数量(小计)]'));
  });
  it('判定顺序：所在函数不允许优先于未勾小计', () => {
    expect(() => parse('SUM([RP0916加工费.备注数(小计)])'))
      .toThrow(new Error('SUM() 里不能再对小计求和：[RP0916加工费.备注数(小计)] 已是整列小计'));
  });
});

describe('F-1 行级函数表达式', () => {
  it('行级表达式内的 (小计) → component_subtotal，可保存，回显逐字不变', () => {
    const expr = 'SUM([RP0916加工费.备注数] * [RP0916宿主.数量] * [RP0916加工费.加工费(小计)])';
    const t = parse(expr);
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'cid-fee', agg: 'SUM', match: MATCH });
    expect(t[0].targetExpr).toEqual([
      { type: 'field', value: '备注数', source: 'cid-fee' },
      { type: 'operator', value: '*' },
      { type: 'b_field', value: '数量' },
      { type: 'operator', value: '*' },
      {
        type: 'component_subtotal', value: '加工费',
        tab_name: 'COMP-9002', component_code: 'COMP-9002', label: 'RP0916加工费·加工费',
      },
    ]);
    expect(checkMappable(t).mappable).toBe(true);
    expect(echo(t)).toBe(expr);
    expect(parse(tokensToDrawerExpression(t, DEFS, SELF))).toEqual(t);
  });

  // ⚠ AC-6b 原文 SUM([RP0916宿主.数量] * [RP0916加工费.加工费(小计)]) 要求「保存成功」，
  //   但 SUM 内只有本页签列 + 整列小计、没有任何来源页签明细列 —— 现行规则（改动前即如此，
  //   已用基线 HEAD 实跑确认）拒绝：「SUM() 行级聚合必须引用至少一个细页签明细列」。
  //   这是 AC 与现行规则的冲突，已报主线裁决；此处固定「当前行为」，裁决后按结论改。
  it('[待主线裁决] AC-6b 原文当前被拒（与改动前同一报错）', () => {
    expect(() => parse('SUM([RP0916宿主.数量] * [RP0916加工费.加工费(小计)])'))
      .toThrow('SUM() 行级聚合必须引用至少一个细页签明细列 [页签别名.字段]');
  });

  it('行级表达式里不带后缀的小计列 → 来源页签列 field（不再是整列小计）', () => {
    const t = parse('SUM([RP0916宿主.数量] * [RP0916加工费.加工费])');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'cid-fee', agg: 'SUM', match: MATCH });
    expect(t[0].targetExpr).toEqual([
      { type: 'b_field', value: '数量' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '加工费', source: 'cid-fee' },
    ]);
  });

  it('行级表达式里的 (小计) 仍按 5.1 校验：未勾小计 → 报错', () => {
    expect(() => parse('SUM([RP0916宿主.数量] * [RP0916加工费.备注数(小计)])'))
      .toThrow(new Error('页签「RP0916加工费」的列「备注数」没有勾选小计，不能写成「(小计)」'));
  });
});

describe('F-2 回显与往返（AC-3③ / AC-4 / AC-13）', () => {
  const EXPR = '[RP0916加工费.加工费] + [RP0916加工费.加工费(小计)] + [RP0916加工费(总计)] + SUM([RP0916加工费.加工费])';

  it('AC-3② 回显文字与输入逐字相同', () => {
    expect(echo(parse(EXPR))).toBe(EXPR);
  });

  it('AC-3③ 回显 → 再解析，token 逐字段相等', () => {
    const t1 = parse(EXPR);
    const t2 = parse(tokensToDrawerExpression(t1, DEFS, SELF));
    expect(t2).toEqual(t1);
  });

  it('存量 component_subtotal（旧 tab_name 为别名）回显带 (小计)，往返逐字段相等', () => {
    const stored: FormulaToken[] = [{
      type: 'component_subtotal', value: '加工费',
      tab_name: 'COMP-9002', component_code: 'COMP-9002', label: 'RP0916加工费·加工费',
    }];
    const text = tokensToDrawerExpression(stored, DEFS, SELF);
    expect(text).toBe('[RP0916加工费.加工费(小计)]');
    expect(parse(text)).toEqual(stored);
  });

  it('tabDefs 为空时回显退回编号，仍带 (小计)（公式列表场景）', () => {
    const t = parse('[RP0916加工费.加工费(小计)]');
    expect(tokensToDrawerExpression(t, [])).toBe('[COMP-9002.加工费(小计)]');
  });

  it('页签合计与空列名回显不变：[名称(总计)]', () => {
    expect(tokensToDrawerExpression(
      [{ type: 'component_subtotal', value: '', tab_name: '', component_code: 'COMP-9002' }], DEFS,
    )).toBe('[RP0916加工费(总计)]');
  });

  it('行级表达式内的整页签合计回显不变', () => {
    const t: FormulaToken[] = [{
      type: 'cross_tab_ref', source: 'cid-fee', sourceLabel: 'RP0916加工费', target: '', agg: 'SUM', match: MATCH,
      targetExpr: [
        { type: 'field', value: '加工费', source: 'cid-fee' },
        { type: 'operator', value: '*' },
        { type: 'component_subtotal', value: '__amount_total__', tab_name: '__amount_total__', component_code: 'COMP-9002', is_tab_total: true },
      ],
    }];
    expect(echo(t)).toBe('SUM([RP0916加工费.加工费] * [RP0916加工费(总计)])');
  });
});

describe('F-3 着色（AC-1~AC-3 / AC-5 / AC-8 / AC-10）', () => {
  const color = (body: string, rkf: string[] = RKF, insideFn = false, insideKsum = false, insideSumif = false) =>
    classifyRefSegment(body, DEFS, rkf, true, insideFn, insideKsum, insideSumif).color;

  it('AC-1 勾了小计的列不带后缀 → 蓝', () => {
    expect(color('RP0916加工费.加工费')).toBe('blue');
  });
  it('AC-2 带 (小计) 且确为小计列 → 黄', () => {
    expect(color('RP0916加工费.加工费(小计)')).toBe('yellow');
  });
  it('AC-5a 未勾小计 → 红', () => {
    expect(color('RP0916加工费.备注数(小计)')).toBe('red');
  });
  it('AC-5b 本页签 → 红', () => {
    expect(color('RP0916宿主.数量(小计)')).toBe('red');
  });
  it('AC-5c 没写列名 → 红', () => {
    expect(color('RP0916加工费(小计)')).toBe('red');
  });
  it('未知页签 (小计) → 红', () => {
    expect(color('不存在.加工费(小计)')).toBe('red');
  });
  it('K 系列 / SUMIF 内的 (小计) → 红；普通函数内仍黄（错在外层，保存时拦）', () => {
    expect(color('RP0916加工费.加工费(小计)', RKF, true, true)).toBe('red');
    expect(color('RP0916加工费.加工费(小计)', RKF, true, false, true)).toBe('red');
    expect(color('RP0916加工费.加工费(小计)', RKF, true)).toBe('yellow');
  });
  it('AC-8b 宿主无行键（小计组件）：明细红、(小计) 黄', () => {
    expect(color('RP0916加工费.加工费', [])).toBe('red');
    expect(color('RP0916加工费.加工费(小计)', [])).toBe('yellow');
  });
  it('AC-7 本页签不带后缀的小计列 → 紫（与明细同规则）', () => {
    expect(color('RP0916宿主.数量')).toBe('purple');
  });
  it('AC-3② 混合串颜色依次 蓝 / 黄 / 绿 / 蓝', () => {
    const segs = parseFormulaSegments(
      '[RP0916加工费.加工费] + [RP0916加工费.加工费(小计)] + [RP0916加工费(总计)] + SUM([RP0916加工费.加工费])',
      DEFS, RKF, true,
    );
    expect(segs.filter((s) => s.isBlock).map((s) => s.color)).toEqual(['blue', 'yellow', 'green', 'blue']);
    expect(segs.filter((s) => s.isBlock).map((s) => s.display)).toEqual([
      'RP0916加工费·加工费', 'RP0916加工费·加工费(小计)', 'RP0916加工费(总计)', 'RP0916加工费·加工费',
    ]);
  });
  it('SUM([宿主.数量] * [加工费.加工费(小计)]) 块依次 紫 / 黄', () => {
    const segs = parseFormulaSegments('SUM([RP0916宿主.数量] * [RP0916加工费.加工费(小计)])', DEFS, RKF, true);
    expect(segs.filter((s) => s.isBlock).map((s) => s.color)).toEqual(['purple', 'yellow']);
  });
  it('Excel（enforceMappable=false）：(小计) 规则相同', () => {
    expect(classifyRefSegment('RP0916加工费.加工费(小计)', DEFS, undefined, false).color).toBe('yellow');
    expect(classifyRefSegment('RP0916加工费.备注数(小计)', DEFS, undefined, false).color).toBe('red');
    expect(classifyRefSegment('RP0916加工费.备注数', DEFS, undefined, false).color).toBe('blue');
  });
});
