/**
 * treeRefGate.test.ts — task-0803 Task 8b：TabJoinFormulaDrawer 的父子取值保存前拦截。
 *
 * F-2/F-7 的拦截逻辑在 save() 内联调用两个导出的纯函数（checkTreeRefTabTypeGate /
 * checkTreeRefInnerWhitelist），这里直接单测这两个函数——与 sumifTokenBuild.test.ts 单测
 * TabJoinFormulaDrawer 导出的 buildSumifToken/buildSumifText 是同一种测试手段（本项目未装
 * @testing-library/react/jsdom，见该目录 RowKeyConflictDrawer.test.tsx 顶部注释），
 * 且这两个函数正是 save() 实际调用的同一份代码，不是另外抄一套校验逻辑测试。
 */
import { describe, it, expect } from 'vitest';
import {
  checkTreeRefTabTypeGate,
  checkTreeRefInnerWhitelist,
  TREE_REF_INNER_VIOLATION_TEXT,
} from '../TabJoinFormulaDrawer';
import { expressionToTokens } from '../../component/formulaSerialize';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import type { FormulaToken } from '../../component/types';

const tabDefs: TabDef[] = [
  {
    alias: 'BOM',
    tabKey: 'cid-bom',
    componentId: 'cid-bom',
    componentName: 'BOM',
    rowKeyFields: ['料号'],
    detailFields: ['用量', '累计用量'],
    allFields: ['料号', '用量', '累计用量'],
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

describe('checkTreeRefTabTypeGate (F-2)', () => {
  it('tabType=BOM 且用了 PGET → 放行(null)', () => {
    expect(checkTreeRefTabTypeGate(parse('PGET(累计用量)'), 'BOM')).toBeNull();
  });

  it('tabType=材质元素（非 BOM）且用了 CSUM → 拦截，文案含人类可读标签「材质元素」', () => {
    const msg = checkTreeRefTabTypeGate(parse('CSUM(用量)'), '材质元素');
    expect(msg).toBe('父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）仅 BOM 类型页签可用（当前页签类型：材质元素）');
  });

  it('tabType=零件/外购件/主件 且用了 PGET → 均拦截，标签逐字对应', () => {
    expect(checkTreeRefTabTypeGate(parse('PGET(用量)'), '零件')).toBe(
      '父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）仅 BOM 类型页签可用（当前页签类型：零件）',
    );
    expect(checkTreeRefTabTypeGate(parse('PGET(用量)'), '外购件')).toBe(
      '父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）仅 BOM 类型页签可用（当前页签类型：外购件）',
    );
    expect(checkTreeRefTabTypeGate(parse('PGET(用量)'), '主件')).toBe(
      '父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）仅 BOM 类型页签可用（当前页签类型：主件）',
    );
  });

  it('tabType 未配置(undefined) 且用了 PGET → 拦截，标签显示「未配置」', () => {
    const msg = checkTreeRefTabTypeGate(parse('PGET(用量)'), undefined);
    expect(msg).toBe('父子取值（PGET/CSUM/CAVG/CMAX/CMIN/CCOUNT）仅 BOM 类型页签可用（当前页签类型：未配置）');
  });

  it('非 BOM 页签但公式里没有 PGET/C* → 放行(null)，不误伤普通公式', () => {
    expect(checkTreeRefTabTypeGate(parse('[用量] + [累计用量]'), '零件')).toBeNull();
    // [层级] 是树属性而非 tree_ref，F-2 只限制 6 个函数，不限制树属性
    expect(checkTreeRefTabTypeGate(parse('[层级]'), '零件')).toBeNull();
  });
});

describe('checkTreeRefInnerWhitelist (F-7)', () => {
  it('targetExpr 只含白名单类型（field/operator/number）→ 放行(null)', () => {
    expect(checkTreeRefInnerWhitelist(parse('CSUM(用量 * 2)'))).toBeNull();
  });

  it('targetExpr 内含跨页签引用（cross_tab_ref）→ 拦截，文案逐字一致', () => {
    const msg = checkTreeRefInnerWhitelist(parse('CSUM([来料.用量])'));
    expect(msg).toBe(TREE_REF_INNER_VIOLATION_TEXT);
    expect(msg).toBe('父子取值的目标表达式内不能再引用跨页签数据或其他父子取值，请改用本页签的列');
  });

  it('targetExpr 内嵌套另一个 tree_ref（PGET 套 CSUM）→ 拦截', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'CHILD',
        agg: 'SUM',
        targetExpr: [
          { type: 'field', value: '用量' },
          { type: 'tree_ref', dir: 'PARENT', agg: 'NONE', targetExpr: [{ type: 'field', value: '累计用量' }] },
        ],
      },
    ];
    expect(checkTreeRefInnerWhitelist(tokens)).toBe(TREE_REF_INNER_VIOLATION_TEXT);
  });

  it('公式中没有 tree_ref token → 天然放行(null)', () => {
    expect(checkTreeRefInnerWhitelist(parse('[用量] + [累计用量]'))).toBeNull();
  });
});
