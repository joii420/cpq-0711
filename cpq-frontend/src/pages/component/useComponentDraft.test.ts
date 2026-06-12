/**
 * localStorage 草稿读写 hook 测试
 *
 * 注意：项目未安装 jsdom，改用手工 mock localStorage（行为完全等价）。
 * 覆盖 globalThis.localStorage，每个 test 前 clear()。
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  draftKey, writeDraft, readDraft, clearDraft, listAllDrafts,
} from './useComponentDraft';
import { buildDraftSnapshot } from './componentDraft';

// ── 轻量 localStorage mock ──────────────────────────────────────────────────
function makeLocalStorage() {
  const store: Record<string, string> = {};
  return {
    getItem: (k: string) => Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null,
    setItem: (k: string, v: string) => { store[k] = v; },
    removeItem: (k: string) => { delete store[k]; },
    clear: () => { Object.keys(store).forEach((k) => delete store[k]); },
    get length() { return Object.keys(store).length; },
    key: (i: number) => Object.keys(store)[i] ?? null,
  };
}

const mockLS = makeLocalStorage();
const origLS = globalThis.localStorage;

beforeEach(() => {
  Object.defineProperty(globalThis, 'localStorage', { value: mockLS, writable: true, configurable: true });
  mockLS.clear();
});
afterEach(() => {
  Object.defineProperty(globalThis, 'localStorage', { value: origLS, writable: true, configurable: true });
});
// ───────────────────────────────────────────────────────────────────────────

const snap = () => buildDraftSnapshot({
  fields: [], formulas: [], dataDriverPath: '$v', rowKeyFields: [],
  excelColumns: [], bomRecursiveExpand: false,
});

describe('useComponentDraft storage', () => {
  it('draftKey 规约', () => {
    expect(draftKey('abc')).toBe('cpq:component-draft:abc');
  });

  it('write → read round-trip', () => {
    writeDraft('c1', snap(), '2026-06-12T00:00:00Z');
    const env = readDraft('c1');
    expect(env?.snapshot.dataDriverPath).toBe('$v');
    expect(env?.baselineUpdatedAt).toBe('2026-06-12T00:00:00Z');
    expect(typeof env?.savedAt).toBe('number');
  });

  it('clearDraft 删除', () => {
    writeDraft('c1', snap(), undefined);
    clearDraft('c1');
    expect(readDraft('c1')).toBeNull();
  });

  it('listAllDrafts 枚举所有草稿（含 componentId）', () => {
    writeDraft('c1', snap(), undefined);
    writeDraft('c2', snap(), undefined);
    mockLS.setItem('unrelated:key', 'x');
    const all = listAllDrafts();
    expect(all.map((d) => d.componentId).sort()).toEqual(['c1', 'c2']);
  });

  it('readDraft 容错：损坏 JSON 返 null 不抛', () => {
    mockLS.setItem(draftKey('bad'), '{not json');
    expect(readDraft('bad')).toBeNull();
  });
});
