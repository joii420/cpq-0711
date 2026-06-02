import { describe, it, expect, vi } from 'vitest';
import { buildTreeRows, isTreeRowHidden, resolveTreeKey, layoutTreeRows } from './treeTable';

describe('buildTreeRows', () => {
  it('单根多层:子行紧跟父行 + depth 正确 + hasChildren 正确', () => {
    const r = buildTreeRows([
      { id: 'A', parent: null },
      { id: 'B', parent: 'A' },
      { id: 'C', parent: 'B' },
      { id: 'D', parent: 'A' },
    ]);
    expect(r.order).toEqual([0, 1, 2, 3]);
    expect(r.depthByIndex).toEqual({ 0: 0, 1: 1, 2: 2, 3: 1 });
    expect([...r.hasChildren].sort()).toEqual([0, 1]);
    expect(r.parentIndexByIndex).toEqual({ 0: null, 1: 0, 2: 1, 3: 0 });
  });

  it('父为空 → 根', () => {
    const r = buildTreeRows([{ id: 'A', parent: '' }, { id: 'B', parent: null }]);
    expect(r.parentIndexByIndex).toEqual({ 0: null, 1: null });
    expect(r.order).toEqual([0, 1]);
  });

  it('父值在批次内缺失 → 升为根(不丢行)', () => {
    const r = buildTreeRows([{ id: 'A', parent: 'GHOST' }, { id: 'B', parent: 'A' }]);
    expect(r.parentIndexByIndex[0]).toBeNull();
    expect(r.parentIndexByIndex[1]).toBe(0);
    expect(r.order.length).toBe(2);
  });

  it('多根并列', () => {
    const r = buildTreeRows([{ id: 'A', parent: null }, { id: 'B', parent: null }]);
    expect(r.order).toEqual([0, 1]);
    expect(r.depthByIndex).toEqual({ 0: 0, 1: 0 });
  });

  it('成环 → 环上行降级为根 + console.warn + 不死循环 + 不丢行', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const r = buildTreeRows([{ id: 'A', parent: 'B' }, { id: 'B', parent: 'A' }]);
    expect(r.order.length).toBe(2);
    expect(r.parentIndexByIndex[0]).toBeNull();
    expect(r.parentIndexByIndex[1]).toBeNull();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('自环(parent==自身) → 根', () => {
    const r = buildTreeRows([{ id: 'A', parent: 'A' }]);
    expect(r.parentIndexByIndex[0]).toBeNull();
    expect(r.order).toEqual([0]);
  });

  it('料号重复 → 父按第一条匹配 + 重复行全部保留', () => {
    const r = buildTreeRows([
      { id: 'A', parent: null },
      { id: 'A', parent: null },
      { id: 'X', parent: 'A' },
    ]);
    expect(r.order.length).toBe(3);
    expect(r.parentIndexByIndex[2]).toBe(0);
  });

  it('同级保持原始顺序(不重排序)', () => {
    const r = buildTreeRows([
      { id: 'A', parent: null },
      { id: 'C', parent: 'A' },
      { id: 'B', parent: 'A' },
    ]);
    expect(r.order).toEqual([0, 1, 2]);
  });

  it('空数组 → 空布局', () => {
    const r = buildTreeRows([]);
    expect(r.order).toEqual([]);
    expect(r.hasChildren.size).toBe(0);
  });
});

describe('isTreeRowHidden', () => {
  const layout = buildTreeRows([
    { id: 'A', parent: null },
    { id: 'B', parent: 'A' },
    { id: 'C', parent: 'B' },
  ]);
  const nodeKeyByIndex = { 0: 'k0', 1: 'k1', 2: 'k2' };

  it('祖先折叠 → 后代隐藏', () => {
    const collapsed = new Set(['k0']);
    const hidden = (i: number) =>
      isTreeRowHidden(i, layout.parentIndexByIndex, nodeKeyByIndex, collapsed);
    expect(hidden(0)).toBe(false);
    expect(hidden(1)).toBe(true);
    expect(hidden(2)).toBe(true);
  });

  it('仅中间节点折叠 → 只隐藏其后代', () => {
    const collapsed = new Set(['k1']);
    const hidden = (i: number) =>
      isTreeRowHidden(i, layout.parentIndexByIndex, nodeKeyByIndex, collapsed);
    expect(hidden(0)).toBe(false);
    expect(hidden(1)).toBe(false);
    expect(hidden(2)).toBe(true);
  });

  it('无折叠 → 全可见', () => {
    const collapsed = new Set<string>();
    expect(isTreeRowHidden(2, layout.parentIndexByIndex, nodeKeyByIndex, collapsed)).toBe(false);
  });
});

describe('resolveTreeKey', () => {
  it('driver BASIC_DATA:优先取 basicDataValues[lookupKey]', () => {
    const field = { name: '料号', field_type: 'BASIC_DATA', basic_data_path: 'mat_part.part_no' } as any;
    const v = resolveTreeKey(field, { 料号: 'OLD' }, { 'mat_part.part_no': 'P001' }, (p: string) => p);
    expect(v).toBe('P001');
  });
  it('basicDataValues 缺键 → 回退 row[name]', () => {
    const field = { name: '料号', field_type: 'BASIC_DATA', basic_data_path: 'mat_part.part_no' } as any;
    const v = resolveTreeKey(field, { 料号: 'P002' }, {}, (p: string) => p);
    expect(v).toBe('P002');
  });
  it('数组值 → 取首元素', () => {
    const field = { name: '料号', field_type: 'BASIC_DATA', basic_data_path: 'x' } as any;
    const v = resolveTreeKey(field, {}, { x: ['A', 'B'] }, (p: string) => p);
    expect(v).toBe('A');
  });
  it('空 → null', () => {
    const field = { name: '料号', field_type: 'INPUT_TEXT' } as any;
    expect(resolveTreeKey(field, {}, undefined, (p: string) => p)).toBeNull();
  });
});

describe('layoutTreeRows', () => {
  it('order.length 不变 + nodeKey 含前缀 + depth/hasChildren 透传', () => {
    const items = [
      { id: 'A', p: null }, { id: 'B', p: 'A' }, { id: 'C', p: null },
    ];
    const res = layoutTreeRows(items, (it) => it.id, (it) => it.p, 'cid1');
    expect(res.rows.length).toBe(3);                       // 不丢行
    expect(res.rows.map(r => r.originalIndex).sort()).toEqual([0, 1, 2]);
    expect(res.nodeKeyByIndex[0]).toBe('cid1::A');
    const rowA = res.rows.find(r => r.originalIndex === 0)!;
    expect(rowA.hasChildren).toBe(true);
    const rowB = res.rows.find(r => r.originalIndex === 1)!;
    expect(rowB.depth).toBe(1);
    expect(rowB.parentIndex).toBe(0);
  });
  it('id 为 null 的行 nodeKey 用 #index 兜底', () => {
    const items = [{ id: null, p: null }];
    const res = layoutTreeRows(items, (it) => it.id, (it) => it.p, 'cid');
    expect(res.nodeKeyByIndex[0]).toBe('cid::#0');
  });
});

describe('resolveTreeKey DATA_SOURCE/BNF_PATH', () => {
  it('DATA_SOURCE+BNF_PATH:取 basicDataValues[lookup(bnf_path)]', () => {
    const field = { name: '父料号', field_type: 'DATA_SOURCE', datasource_binding: { type: 'BNF_PATH', bnf_path: 'mat_bom.parent_no' } } as any;
    const v = resolveTreeKey(field, {}, { 'mat_bom.parent_no': 'P000' }, (p: string) => p);
    expect(v).toBe('P000');
  });
});
