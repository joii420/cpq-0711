import { describe, expect, it } from 'vitest';
import { stripFieldKeys } from '../componentDraft';
import { newFormulaRow, type FormulaItem } from '../types';

/**
 * BL-0098：公式稳定 id 的前端往返保证。
 *
 * 这些不变量一旦破坏，后端 update 会给每条公式重新生成 id，
 * 导致存量 formula_id 全部失配 —— 那些公式列会静默不出值，且不报任何错。
 */
describe('BL-0098 公式 id 往返', () => {
  it('stripFieldKeys 只剥 key，公式 id 必须原样保留', () => {
    const formulas: FormulaItem[] = [
      { key: 'formula-1', id: 'id-A', name: '公式A', expression: [] },
      { key: 'formula-2', id: 'id-B', name: '公式B', expression: [] },
    ];
    expect(stripFieldKeys(formulas)).toEqual([
      { id: 'id-A', name: '公式A', expression: [] },
      { id: 'id-B', name: '公式B', expression: [] },
    ]);
  });

  it('加载映射 {...f, key} 必须保留 id（模拟 ComponentManagement 的 setFormulas）', () => {
    const loaded = [{ id: 'id-A', name: '公式A', expression: [] }];
    const mapped = loaded.map((f: any, i: number) => ({ ...f, key: `formula-${i}` }));
    expect(mapped[0].id).toBe('id-A');
  });

  it('保存映射 ({key, ...rest}) 必须保留 id（模拟 ComponentManagement 的 cleanFormulas）', () => {
    const state: FormulaItem[] = [{ key: 'formula-0', id: 'id-A', name: '公式A', expression: [] }];
    const clean = state.map(({ key: _k, ...rest }) => rest);
    expect(clean[0]).toHaveProperty('id', 'id-A');
    expect(clean[0]).not.toHaveProperty('key');
  });

  it('newFormulaRow 新建即带 id —— 否则抽屉在公式未保存时没有 id 可绑', () => {
    const a = newFormulaRow();
    const b = newFormulaRow();
    expect(a.id).toBeTruthy();
    expect(b.id).toBeTruthy();
    expect(a.id).not.toBe(b.id);
  });
});
