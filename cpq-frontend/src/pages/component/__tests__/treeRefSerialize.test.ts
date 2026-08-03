import { describe, it, expect } from 'vitest';
import { validateTreeRefWhitelist } from '../formulaSerialize';
import type { FormulaToken } from '../types';

/**
 * task-0803 Task 6：tree_ref.targetExpr 内层白名单校验（前端序列化侧镜像后端
 * TokenMappabilityValidator.TREE_REF_INNER_ALLOWED_TYPES）。
 *
 * 白名单：field/operator/number/bracket_open/bracket_close/global_variable/tree_attr。
 * 必拒：嵌套 tree_ref / cross_tab_ref / component_subtotal / b_field / previous_row_subtotal。
 *
 * 关键场景（2026-08-03 后端评审教训）：tree_ref 嵌在 cross_tab_ref.targetExpr 内部时，
 * 校验必须递归下钻才能捕获——只扫顶层 token 会让它绕过。
 */
describe('validateTreeRefWhitelist (task-0803 Task 6)', () => {
  it('放行：targetExpr 只含白名单 token（field/operator/number/bracket/global_variable/tree_attr 混合）', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'CHILD',
        agg: 'SUM',
        targetExpr: [
          { type: 'bracket_open' },
          { type: 'field', value: '用量' },
          { type: 'operator', value: '*' },
          { type: 'field', value: '单价' },
          { type: 'bracket_close' },
          { type: 'operator', value: '+' },
          { type: 'number', value: '1' },
          { type: 'operator', value: '+' },
          { type: 'global_variable', code: 'ELEM_PRICE' },
          { type: 'operator', value: '+' },
          { type: 'tree_attr', attr: 'LVL' },
        ],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(true);
  });

  it('拒绝：targetExpr 内嵌 tree_ref（禁止 PGET/C* 套 PGET/C*）', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'CHILD',
        agg: 'SUM',
        targetExpr: [
          { type: 'field', value: '用量' },
          { type: 'operator', value: '+' },
          {
            type: 'tree_ref',
            dir: 'PARENT',
            agg: 'NONE',
            targetExpr: [{ type: 'field', value: '累计用量' }],
          },
        ],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('tree_ref');
  });

  it('拒绝：targetExpr 内含 cross_tab_ref', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'CHILD',
        agg: 'SUM',
        targetExpr: [
          {
            type: 'cross_tab_ref',
            source: '回料',
            agg: 'SUM',
            match: [{ a: '料号', b: '料号' }],
          },
        ],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('cross_tab_ref');
  });

  it('拒绝：targetExpr 内含 component_subtotal', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'PARENT',
        agg: 'NONE',
        targetExpr: [{ type: 'component_subtotal', value: '投料' }],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('component_subtotal');
  });

  it('拒绝：targetExpr 内含 b_field', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'PARENT',
        agg: 'NONE',
        targetExpr: [{ type: 'b_field', value: '料号' }],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('b_field');
  });

  it('拒绝：targetExpr 内含 previous_row_subtotal', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'PARENT',
        agg: 'NONE',
        targetExpr: [{ type: 'previous_row_subtotal' } as unknown as FormulaToken],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('previous_row_subtotal');
  });

  it('拒绝（递归场景）：tree_ref 嵌在 cross_tab_ref.targetExpr 内 —— 只扫顶层会漏检', () => {
    // 顶层 token 是 cross_tab_ref（不是 tree_ref），tree_ref 藏在它的 targetExpr 数组里。
    // 若校验只在“遇到顶层 tree_ref 时才检查”，这个 cross_tab_ref 顶层 token 会被直接放行，
    // 里面违规的 tree_ref（其 targetExpr 内含非法的 cross_tab_ref）永远不会被摸到。
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: '其他费用',
        agg: 'SUM',
        match: [{ a: '料号', b: '料号' }],
        targetExpr: [
          { type: 'field', value: '数量' },
          { type: 'operator', value: '*' },
          {
            type: 'tree_ref',
            dir: 'CHILD',
            agg: 'SUM',
            targetExpr: [
              { type: 'field', value: '用量' },
              // 违规：cross_tab_ref 不在 tree_ref 内层白名单里
              {
                type: 'cross_tab_ref',
                source: '回料',
                agg: 'SUM',
                match: [{ a: '料号', b: '料号' }],
              },
            ],
          },
        ],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(false);
    expect(result.reason).toContain('cross_tab_ref');
  });

  it('放行：tree_attr 出现在 targetExpr 内（它在白名单里）', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'tree_ref',
        dir: 'PARENT',
        agg: 'NONE',
        targetExpr: [{ type: 'tree_attr', attr: 'IS_LEAF' }],
      },
    ];
    const result = validateTreeRefWhitelist(tokens);
    expect(result.valid).toBe(true);
  });

  it('放行：null / 空数组 targetExpr（无 tree_ref token 时天然合规）', () => {
    expect(validateTreeRefWhitelist(null).valid).toBe(true);
    expect(validateTreeRefWhitelist(undefined).valid).toBe(true);
    expect(validateTreeRefWhitelist([]).valid).toBe(true);
  });
});
