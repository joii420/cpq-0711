import { describe, it, expect } from 'vitest';
import {
  buildDraftSnapshot,
  stripFieldKeys,
  rebuildFieldKeys,
  isDraftStale,
  type DraftSnapshot,
} from './componentDraft';
import type { ComponentItem } from './types';

const baseComp = (over: Partial<ComponentItem> = {}): ComponentItem => ({
  id: 'c1', name: 'N', code: 'C', columnCount: 0, status: 'ACTIVE',
  componentType: 'NORMAL', fields: [], formulas: [],
  ...over,
});

describe('componentDraft', () => {
  it('buildDraftSnapshot 收齐所有可编辑态字段', () => {
    const snap = buildDraftSnapshot({
      fields: [{ key: 'field-0-123', name: 'a', field_type: 'INPUT_TEXT' }] as any,
      formulas: [{ key: 'formula-0-9', name: 'f', expression: [] }] as any,
      dataDriverPath: '$v',
      rowKeyFields: ['a'],
      excelColumns: [{ col_key: 'x' }] as any,
      bomRecursiveExpand: true,
    });
    expect(snap.fields[0]).not.toHaveProperty('key');
    expect(snap.fields[0].name).toBe('a');
    expect(snap.dataDriverPath).toBe('$v');
    expect(snap.rowKeyFields).toEqual(['a']);
    expect(snap.bomRecursiveExpand).toBe(true);
  });

  it('stripFieldKeys / rebuildFieldKeys round-trip 恢复后每个字段有唯一 key', () => {
    const stripped = stripFieldKeys([{ key: 'k1', name: 'a' } as any, { key: 'k2', name: 'b' } as any]);
    expect(stripped[0]).not.toHaveProperty('key');
    const rebuilt = rebuildFieldKeys(stripped);
    expect(rebuilt[0].key).toBeTruthy();
    expect(rebuilt[1].key).toBeTruthy();
    expect(rebuilt[0].key).not.toBe(rebuilt[1].key);
  });

  it('isDraftStale：服务端基线快照与当前服务端态不同 → 陈旧', () => {
    const draft: DraftSnapshot = buildDraftSnapshot({
      fields: [], formulas: [], dataDriverPath: '$v', rowKeyFields: [],
      excelColumns: [], bomRecursiveExpand: false,
    });
    const baselineServer = baseComp({ updatedAt: '2026-06-12T00:00:00Z' });
    const freshSame = baseComp({ updatedAt: '2026-06-12T00:00:00Z' });
    const freshChanged = baseComp({ updatedAt: '2026-06-12T09:00:00Z' });
    expect(isDraftStale(baselineServer.updatedAt, freshSame.updatedAt)).toBe(false);
    expect(isDraftStale(baselineServer.updatedAt, freshChanged.updatedAt)).toBe(true);
    void draft;
  });
});
