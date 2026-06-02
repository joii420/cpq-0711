import { describe, it, expect, vi } from 'vitest';
import { buildTreeRows } from './treeTable';

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
