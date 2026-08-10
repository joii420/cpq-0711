import { describe, it, expect } from 'vitest';
import { extractSourceRefs, topoOrderComponents, buildComponentDeps } from './crossTabOrder';
import type { TabDepInput } from './crossTabOrder';

describe('crossTabOrder', () => {
  it('extract refs', () => {
    const formulas = [{ expression: [{ type: 'cross_tab_ref', source: 'A' }] }];
    expect(extractSourceRefs(formulas)).toEqual(['A']);
  });
  it('extract refs dedupes and ignores non-cross_tab tokens', () => {
    const formulas = [
      { expression: [{ type: 'field', value: 'x' }, { type: 'cross_tab_ref', source: 'A' }] },
      { expression: [{ type: 'cross_tab_ref', source: 'A' }, { type: 'cross_tab_ref', source: 'B' }] },
    ];
    expect(extractSourceRefs(formulas).sort()).toEqual(['A', 'B']);
  });
  it('extract refs handles empty/undefined', () => {
    expect(extractSourceRefs(undefined)).toEqual([]);
    expect(extractSourceRefs([])).toEqual([]);
  });
  it('topo A before B', () => {
    const order = topoOrderComponents(['B', 'A'], { B: ['A'] });
    expect(order.indexOf('A')).toBeLessThan(order.indexOf('B'));
  });
  it('topo no deps keeps input order', () => {
    expect(topoOrderComponents(['A', 'B', 'C'], {})).toEqual(['A', 'B', 'C']);
  });
  it('topo chain C->B->A yields A,B,C', () => {
    expect(topoOrderComponents(['C', 'B', 'A'], { C: ['B'], B: ['A'] })).toEqual(['A', 'B', 'C']);
  });
  it('cycle throws', () => {
    expect(() => topoOrderComponents(['A', 'B'], { A: ['B'], B: ['A'] })).toThrow();
  });
  it('dep not in ids is ignored (no constraint, no crash)', () => {
    expect(topoOrderComponents(['A'], { A: ['Z'] })).toEqual(['A']);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// repair-0808 · buildComponentDeps 建图规则单测（对应 FR-1 / AC-5，test.md T-1.1~T-1.14）
//
// 契约见 fronttask.md §2.2：cid = componentId||componentCode||tabName（与调用方 ids 同源）；
// component_subtotal 是否建边取决于被引用列是否「顺序敏感」（FORMULA/*_FORMULA → 敏感 → 建边；
// 其它类型 → 不敏感 → 不建边）；判不出（列缺失/字段表不可读/查无此列/整页签合计）一律保守建边；
// cross_tab_ref 恒建边，不做列粒度豁免；自引用 / 卡片外引用不建边、不抛错。
// ─────────────────────────────────────────────────────────────────────────────
describe('buildComponentDeps（repair-0808 · T-1.1~T-1.14）', () => {
  it('T-1.1 component_subtotal 引用 B 的 FORMULA 列 → deps[A] 含 B', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'FORMULA' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.2 component_subtotal 引用 B 的 INPUT_NUMBER 列 → deps[A] 不含 B', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).not.toContain('B');
  });

  it('T-1.3 引用 B 的 LIST_FORMULA 列 → deps[A] 含 B（*_FORMULA 后缀判据生效，D-4）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'LIST_FORMULA' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.4 引用整页签合计（is_tab_total=true）→ deps[A] 含 B（不看列类型）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [
        { type: 'component_subtotal', component_code: 'B', value: 'anything', is_tab_total: true },
      ] }],
    };
    // B 的列全是非公式列——若判据看了列类型会误判不建边，验证 tabTotal 优先短路。
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'anything', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it("T-1.5 引用 value='__amount_total__'（未带 is_tab_total）→ deps[A] 含 B", () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: '__amount_total__' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.6 引用 B 里不存在的列名 → deps[A] 含 B（保守建边，D-3）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: '没有这一列' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.7 B 的 fields 为 undefined → deps[A] 含 B（保守建边，D-3）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: undefined };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.7b B 的 fields 为非数组（畸形快照）→ deps[A] 含 B（保守建边，D-3）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: 'not-an-array' as any };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.8 token 的 value 为空串 → deps[A] 含 B（保守建边，D-3）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: '' }] }],
    };
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.9 cross_tab_ref 指向 B 的任意列（含 INPUT 列）→ deps[A] 含 B（cross_tab 恒建边，不做列豁免）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'cross_tab_ref', source: 'B' }] }],
    };
    // B 全是 INPUT_NUMBER 列——若 cross_tab_ref 也做了列粒度豁免，这里就会漏边。
    const B: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');
  });

  it('T-1.10 component_subtotal 指向卡片外的 code → 不建边、不抛错', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'ZZZ-NOT-IN-CARD', value: 'col' }] }],
    };
    expect(() => buildComponentDeps([A])).not.toThrow();
    const deps = buildComponentDeps([A]);
    expect(deps['A']).toEqual([]);
  });

  it('T-1.11 A 的公式引用 A 自己的列小计（二阶列）→ deps[A] 不含 A（自引用不建边）', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [
        { type: 'component_subtotal', component_code: 'A', value: 'selfCol' },
      ] }],
      fields: [{ name: 'selfCol', field_type: 'FORMULA' }],
    };
    const deps = buildComponentDeps([A]);
    expect(deps['A']).not.toContain('A');
  });

  it('T-1.12 目标只能用 tab_name 解析（component_code 缺失）→ 按 tabName 解析成功并按列粒度判定', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      // 无 component_code，只有 tab_name —— 解析必须回退到 tabName 映射。
      formulas: [{ expression: [{ type: 'component_subtotal', tab_name: 'TabB', value: 'col' }] }],
    };
    // B 本身也没有 code（component_code 缺失场景常见于模板改过 / 未回填），只能靠 tabName 命中。
    const B: TabDepInput = { cid: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'FORMULA' }] };
    const deps = buildComponentDeps([A, B]);
    expect(deps['A']).toContain('B');

    // 列粒度判定同样在 tabName 解析路径生效：换成 INPUT_NUMBER 列就不该建边。
    const B_input: TabDepInput = { cid: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'INPUT_NUMBER' }] };
    const depsInput = buildComponentDeps([A, B_input]);
    expect(depsInput['A']).not.toContain('B');
  });

  it('T-1.13 fields 用 camelCase fieldType 写法 → 与 snake field_type 判定结果一致', () => {
    const A: TabDepInput = {
      cid: 'A', code: 'A', tabName: 'TabA',
      formulas: [{ expression: [{ type: 'component_subtotal', component_code: 'B', value: 'col' }] }],
    };
    const B_snake: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', field_type: 'FORMULA' }] };
    const B_camel: TabDepInput = { cid: 'B', code: 'B', tabName: 'TabB', fields: [{ name: 'col', fieldType: 'FORMULA' } as any] };
    const depsSnake = buildComponentDeps([A, B_snake]);
    const depsCamel = buildComponentDeps([A, B_camel]);
    expect(depsSnake['A']).toContain('B');
    expect(depsCamel['A']).toContain('B');
    expect(depsCamel['A']).toEqual(depsSnake['A']);
  });

  it('T-1.14 空 formulas / 空 fields 页签 → deps[cid] = []，不抛错', () => {
    const A: TabDepInput = { cid: 'A', code: 'A', tabName: 'TabA', formulas: [], fields: [] };
    expect(() => buildComponentDeps([A])).not.toThrow();
    const deps = buildComponentDeps([A]);
    expect(deps['A']).toEqual([]);
  });
});
