import { describe, it, expect } from 'vitest';
import {
  computeTabFormulasTree, usesTreeTokensTab, isBomTreeRowSet,
  computeAllFormulas, type ComponentDataItem, type TreeFormulaRowInput,
} from './QuotationStep2';

// ─── 夹具构造 helper ───────────────────────────────────────────────────────

/** 一个 tree_ref token（PGET/C*）。 */
function treeRef(dir: 'PARENT' | 'CHILD', targetField: string, agg: 'NONE' | 'SUM' | 'AVG' | 'MAX' | 'MIN' | 'COUNT' = dir === 'PARENT' ? 'NONE' : 'SUM') {
  return { type: 'tree_ref', dir, agg, targetExpr: [{ type: 'field', value: targetField }] };
}

/** 一个 tree_attr token。 */
function treeAttr(attr: 'LVL' | 'IS_LEAF' | 'IS_ROOT') {
  return { type: 'tree_attr', attr };
}

function field(name: string, type: string, extra: Record<string, any> = {}) {
  return { name, field_type: type, ...extra };
}

function row(vals: Record<string, any>, nodeId: string, parentId: string | null, lvl: number): TreeFormulaRowInput {
  return { row: vals, nodeId, parentId, lvl };
}

// ─── 三层树夹具：root n1 → 子 n2/n3 → 孙 n4(挂 n2 下) ───────────────────────
//   n1: 数值=100(根自身也带数值列,供 PGET 根返回值场景外的辅助校验)
//   n2: 数值=10   n3: 数值=20   n4: 数值=5(n2 的子)
function threeLevelComp(formulaFieldName: string, expr: any[]) {
  const comp: ComponentDataItem = {
    componentId: 'c1', componentCode: 'C1', tabName: 'BOM页签',
    fields: [
      field('数值', 'INPUT_NUMBER'),
      field(formulaFieldName, 'FORMULA'),
    ],
    formulas: [{ name: 'f1', expression: expr }],
    rows: [],
    subtotal: 0,
  } as any;
  return comp;
}

function threeLevelRows(vals: { n1?: number; n2?: number; n3?: number; n4?: number } = {}): TreeFormulaRowInput[] {
  return [
    row({ 数值: vals.n1 ?? 100 }, 'n1', null, 0),
    row({ 数值: vals.n2 ?? 10 }, 'n2', 'n1', 1),
    row({ 数值: vals.n3 ?? 20 }, 'n3', 'n1', 1),
    row({ 数值: vals.n4 ?? 5 }, 'n4', 'n2', 2),
  ];
}

// 需给 FORMULA 字段绑正确公式名，与 fields[].name 一致（无 formula_id/formula_name 时按名精确匹配）。
function withFormulaFieldName(comp: ComponentDataItem, name: string) {
  (comp.fields[1] as any).name = name;
  (comp.formulas[0] as any).name = name;
  return comp;
}

describe('computeTabFormulasTree — CSUM 只算直接子（不含孙辈）', () => {
  it('三层树：根的 CSUM(数值) = 两个直接子之和，不含孙辈 n4', () => {
    const comp = withFormulaFieldName(threeLevelComp('汇总', [treeRef('CHILD', '数值', 'SUM')]), '汇总');
    const rows = threeLevelRows();
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['汇总']).toBe(30); // n2(10) + n3(20)，不含 n4(5)
    expect(out[1]['汇总']).toBe(5);  // n2 的直接子只有 n4
    expect(out[2]['汇总']).toBe(0);  // n3 是叶子
    expect(out[3]['汇总']).toBe(0);  // n4 是叶子
  });
});

describe('computeTabFormulasTree — PGET 取父行值 / 根行返 0', () => {
  it('子行 PGET(数值) = 父行数值实际值；根行 PGET = 0', () => {
    const comp = withFormulaFieldName(threeLevelComp('父值', [treeRef('PARENT', '数值')]), '父值');
    const rows = threeLevelRows();
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['父值']).toBe(0);   // 根行无父
    expect(out[1]['父值']).toBe(100); // n2 的父是 n1(数值=100)
    expect(out[2]['父值']).toBe(100); // n3 的父也是 n1
    expect(out[3]['父值']).toBe(10);  // n4 的父是 n2(数值=10)
  });
});

describe('computeTabFormulasTree — 叶子行 C* 全部返 0', () => {
  it('叶子行 CSUM/CAVG/CMAX/CMIN/CCOUNT 全部 = 0', () => {
    const rows = threeLevelRows();
    for (const agg of ['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'] as const) {
      const comp = withFormulaFieldName(threeLevelComp('聚合', [treeRef('CHILD', '数值', agg)]), '聚合');
      const out = computeTabFormulasTree(comp, rows);
      expect(out[3]['聚合']).toBe(0); // n4 是叶子
      expect(out[2]['聚合']).toBe(0); // n3 是叶子
    }
  });
});

describe('computeTabFormulasTree — 「有值」判据（§4.3.4，最容易做错的一处）', () => {
  // 父 R，三个直接子 A/B/C
  function parentChildComp(agg: 'SUM' | 'AVG' | 'MAX' | 'MIN' | 'COUNT') {
    return withFormulaFieldName(
      threeLevelComp('聚合', [treeRef('CHILD', '数值', agg)]), '聚合');
  }
  function parentThreeChildren(vals: Array<number | null>): TreeFormulaRowInput[] {
    const out: TreeFormulaRowInput[] = [row({}, 'R', null, 0)];
    vals.forEach((v, i) => {
      out.push(row(v === null ? {} : { 数值: v }, `c${i}`, 'R', 1));
    });
    return out;
  }

  it('CMIN 认 0 为有值：子行 0/5/8 → CMIN=0（不是 5）', () => {
    const comp = parentChildComp('MIN');
    const rows = parentThreeChildren([0, 5, 8]);
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['聚合']).toBe(0);
  });

  it('CMIN 不被空行拉成 0：子行 5/空/8 → CMIN=5（不是 0）', () => {
    const comp = parentChildComp('MIN');
    const rows = parentThreeChildren([5, null, 8]);
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['聚合']).toBe(5);
  });

  it('CAVG 分母只数有值行：5/空/8 → 6.5（分母=2，不是 3）', () => {
    const comp = parentChildComp('AVG');
    const rows = parentThreeChildren([5, null, 8]);
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['聚合']).toBe(6.5);
  });

  it('CCOUNT 只数有值行：5/空/8 → 2', () => {
    const comp = parentChildComp('COUNT');
    const rows = parentThreeChildren([5, null, 8]);
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['聚合']).toBe(2);
  });

  it('判据 5：targetExpr 无 field token（纯常量）→ 所有未删除子行均视为有值，CSUM(1) = 子行数', () => {
    const comp = withFormulaFieldName(
      threeLevelComp('计数', [{ type: 'tree_ref', dir: 'CHILD', agg: 'SUM', targetExpr: [{ type: 'number', value: '1' }] }]),
      '计数');
    const rows = parentThreeChildren([5, null, 8]); // 3 个子行，含 1 个"数值"列为空的
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['计数']).toBe(3); // CSUM(1) = 子行数，不受"数值"列是否有值影响
  });

  it('判据 6：引用的列是公式列 → 恒有值（哪怕算出 0），CAVG 分母含它', () => {
    // 子行各自有一个公式列「成本」= 数值 * 1（无值时按 0 参与运算，但恒有值）。
    const comp: ComponentDataItem = {
      componentId: 'c2', componentCode: 'C2', tabName: 'BOM页签',
      fields: [
        field('数值', 'INPUT_NUMBER'),
        field('成本', 'FORMULA'),
        field('均值', 'FORMULA'),
      ],
      formulas: [
        { name: 'f_cost', expression: [{ type: 'field', value: '数值' }] },
        { name: 'f_avg', expression: [treeRef('CHILD', '成本', 'AVG')] },
      ],
      rows: [],
      subtotal: 0,
    } as any;
    (comp.fields[1] as any).name = '成本';
    (comp.formulas[0] as any).name = '成本';
    (comp.fields[2] as any).name = '均值';
    (comp.formulas[1] as any).name = '均值';

    const rows = parentThreeChildren([10, null, 20]); // B 行"数值"为空 → 成本(公式列)算出 0，恒有值
    const out = computeTabFormulasTree(comp, rows);
    // A: 成本=10, B: 成本=0(公式列恒有值), C: 成本=20 → 均值 = (10+0+20)/3 = 10（分母含 B）
    expect(out[1]['成本']).toBe(10);
    expect(out[2]['成本']).toBe(0);
    expect(out[3]['成本']).toBe(20);
    expect(out[0]['均值']).toBe(10);
  });
});

describe('computeTabFormulasTree — 双向混用（同页签一列 CSUM、一列 PGET 都算对）', () => {
  it('「子项汇总」列自下而上(CSUM)，「父项用量」列自上而下(PGET)，同页签共存互不干扰', () => {
    const comp: ComponentDataItem = {
      componentId: 'c3', componentCode: 'C3', tabName: 'BOM页签',
      fields: [
        field('数值', 'INPUT_NUMBER'),
        field('子项汇总', 'FORMULA'), // CSUM(数值) —— bottom-up
        field('父项用量', 'FORMULA'), // PGET(数值) —— top-down
      ],
      formulas: [
        { name: '子项汇总', expression: [treeRef('CHILD', '数值', 'SUM')] },
        { name: '父项用量', expression: [treeRef('PARENT', '数值')] },
      ],
      rows: [],
      subtotal: 0,
    } as any;

    const rows = threeLevelRows({ n1: 100, n2: 10, n3: 20, n4: 5 });
    const out = computeTabFormulasTree(comp, rows);
    // CSUM 方向：根汇总直接子(10+20=30，不含孙 n4=5)
    expect(out[0]['子项汇总']).toBe(30);
    expect(out[1]['子项汇总']).toBe(5); // n2 的直接子只有 n4=5
    // PGET 方向：n2/n3 的父项用量 = 根的数值(100)；n4 的父项用量 = n2 的数值(10)
    expect(out[1]['父项用量']).toBe(100);
    expect(out[2]['父项用量']).toBe(100);
    expect(out[3]['父项用量']).toBe(10);
    expect(out[0]['父项用量']).toBe(0); // 根无父
  });
});

describe('computeTabFormulasTree — 成环：环上列置 0，其他列正常', () => {
  it('A=PGET(B) 且 B=CSUM(A) 成环 → 该行 A/B 置 0；不相关列 C 仍正常求值', () => {
    const comp: ComponentDataItem = {
      componentId: 'c4', componentCode: 'C4', tabName: 'BOM页签',
      fields: [
        field('数值', 'INPUT_NUMBER'),
        field('A', 'FORMULA'),
        field('B', 'FORMULA'),
        field('C', 'FORMULA'), // 无关列：直接引用「数值」，不涉及环
      ],
      formulas: [
        { name: 'A', expression: [treeRef('PARENT', 'B')] },
        { name: 'B', expression: [treeRef('CHILD', 'A', 'SUM')] },
        { name: 'C', expression: [{ type: 'field', value: '数值' }] },
      ],
      rows: [],
      subtotal: 0,
    } as any;
    const rows = threeLevelRows();
    const out = computeTabFormulasTree(comp, rows);
    // A/B 互相依赖对方在其他行的值 → 环，全部置 0
    for (let i = 0; i < rows.length; i++) {
      expect(out[i]['A']).toBe(0);
      expect(out[i]['B']).toBe(0);
    }
    // C 与环无依赖关系，正常求值
    expect(out[0]['C']).toBe(100);
    expect(out[1]['C']).toBe(10);
    expect(out[2]['C']).toBe(20);
    expect(out[3]['C']).toBe(5);
  });
});

describe('computeTabFormulasTree — 树属性 tree_attr', () => {
  it('[层级]/[是否叶子]/[是否根] 按树结构返回正确值', () => {
    const comp: ComponentDataItem = {
      componentId: 'c5', componentCode: 'C5', tabName: 'BOM页签',
      fields: [
        field('层级', 'FORMULA'),
        field('是否叶子', 'FORMULA'),
        field('是否根', 'FORMULA'),
      ],
      formulas: [
        { name: '层级', expression: [treeAttr('LVL')] },
        { name: '是否叶子', expression: [treeAttr('IS_LEAF')] },
        { name: '是否根', expression: [treeAttr('IS_ROOT')] },
      ],
      rows: [],
      subtotal: 0,
    } as any;
    const rows = threeLevelRows();
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]).toMatchObject({ 层级: 0, 是否叶子: 0, 是否根: 1 });
    expect(out[1]).toMatchObject({ 层级: 1, 是否叶子: 0, 是否根: 0 }); // n2 有子 n4
    expect(out[2]).toMatchObject({ 层级: 1, 是否叶子: 1, 是否根: 0 }); // n3 叶子
    expect(out[3]).toMatchObject({ 层级: 2, 是否叶子: 1, 是否根: 0 }); // n4 叶子
  });
});

describe('computeTabFormulasTree — 悬空父引用（父不存在 / 父被墓碑排除）统一按"无父"处理', () => {
  it('parentId 指向不在行集中的节点 → 该行视为根：[是否根]=1，PGET=0', () => {
    const comp: ComponentDataItem = {
      componentId: 'c6', componentCode: 'C6', tabName: 'BOM页签',
      fields: [field('数值', 'INPUT_NUMBER'), field('父值', 'FORMULA'), field('是否根', 'FORMULA')],
      formulas: [
        { name: '父值', expression: [treeRef('PARENT', '数值')] },
        { name: '是否根', expression: [treeAttr('IS_ROOT')] },
      ],
      rows: [],
      subtotal: 0,
    } as any;
    // 单行，parentId 指向一个根本不存在的节点 'ghost'
    const rows: TreeFormulaRowInput[] = [row({ 数值: 5 }, 'x1', 'ghost', 0)];
    const out = computeTabFormulasTree(comp, rows);
    expect(out[0]['父值']).toBe(0);
    expect(out[0]['是否根']).toBe(1);
  });
});

describe('路由判据：usesTreeTokensTab / isBomTreeRowSet', () => {
  it('非 BOM 页签（公式无父子 token）：usesTreeTokensTab 返 false，调用方应走原 computeAllFormulas 路径', () => {
    const comp: ComponentDataItem = {
      componentId: 'c7', componentCode: 'C7', tabName: '普通页签',
      fields: [field('单价', 'INPUT_NUMBER'), field('金额', 'FORMULA')],
      formulas: [{ name: '金额', expression: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'number', value: '2' }] }],
      rows: [],
      subtotal: 0,
    } as any;
    expect(usesTreeTokensTab(comp)).toBe(false);

    // 且改造前后 computeAllFormulas 本身结果不变（零回归门禁的直接证据）。
    const result = computeAllFormulas(comp, { 单价: 100 });
    expect(result['金额']).toBe(200);
  });

  it('行集不带 nodeId → isBomTreeRowSet 返 false', () => {
    expect(isBomTreeRowSet([{ row: { 单价: 1 } } as TreeFormulaRowInput])).toBe(false);
  });

  it('行集带非空 nodeId → isBomTreeRowSet 返 true', () => {
    expect(isBomTreeRowSet(threeLevelRows())).toBe(true);
  });

  it('BOM 树页签但公式未用父子 token → usesTreeTokensTab 仍返 false（任一不满足不路由）', () => {
    const comp: ComponentDataItem = {
      componentId: 'c8', componentCode: 'C8', tabName: 'BOM页签',
      fields: [field('数值', 'INPUT_NUMBER'), field('翻倍', 'FORMULA')],
      formulas: [{ name: '翻倍', expression: [{ type: 'field', value: '数值' }, { type: 'operator', value: '*' }, { type: 'number', value: '2' }] }],
      rows: [],
      subtotal: 0,
    } as any;
    expect(isBomTreeRowSet(threeLevelRows())).toBe(true);
    expect(usesTreeTokensTab(comp)).toBe(false); // 行是树，但公式没用父子 token → 仍不路由
  });
});

describe('闸⑤求值期兜底：拿不到树上下文（走 computeAllFormulas 时）→ 0，不抛异常', () => {
  it('存量脏数据场景（tree_ref/tree_attr 混进非树求值路径）：computeAllFormulas 恒返 0，不崩溃', () => {
    // computeAllFormulas 从不构建/传递 treeCtx（该参数只在 computeTabFormulasTree 内部使用），
    // 因此哪怕公式字段的表达式里意外混入 tree_ref/tree_attr token，evaluateExpression 内部
    // 的 evalTreeRefToken/evalTreeAttrToken 也会因 ctx===undefined 直接返 0（镜像后端 AC-20）。
    const comp: ComponentDataItem = {
      componentId: 'c9', componentCode: 'C9', tabName: '任意页签',
      fields: [
        field('数值', 'INPUT_NUMBER'),
        field('父值', 'FORMULA'),
        field('子聚合', 'FORMULA'),
        field('层级', 'FORMULA'),
      ],
      formulas: [
        { name: '父值', expression: [treeRef('PARENT', '数值')] },
        { name: '子聚合', expression: [treeRef('CHILD', '数值', 'SUM')] },
        { name: '层级', expression: [treeAttr('LVL')] },
      ],
      rows: [],
      subtotal: 0,
    } as any;
    const result = computeAllFormulas(comp, { 数值: 42 });
    expect(result['父值']).toBe(0);
    expect(result['子聚合']).toBe(0);
    expect(result['层级']).toBe(0);
  });
});
