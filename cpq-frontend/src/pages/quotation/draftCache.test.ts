/**
 * safeSetLocalDraft 回归测试(2026-06-26)。
 *
 * 守护「localStorage 配额超限致整页空白 + 真实丢数据」的修复:超配额时 setItem 抛
 * QuotaExceededError,safeSetLocalDraft 必须吞掉、清旧值、绝不抛出 —— 否则异常冒泡到
 * loadQuotation 的后端失败 catch,用空缓存覆盖已渲染内容 → 用户再保存 → 全删全建丢数据。
 *
 * 项目未装 jsdom,手工 mock localStorage(行为等价)。
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { safeSetLocalDraft, draftCacheKey } from './draftCache';

function makeLocalStorage(opts?: { throwOnSet?: boolean }) {
  const store: Record<string, string> = {};
  return {
    _store: store,
    getItem: (k: string) => (Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null),
    setItem: (k: string, v: string) => {
      if (opts?.throwOnSet) {
        const e: any = new Error("Failed to execute 'setItem' on 'Storage': exceeded the quota.");
        e.name = 'QuotaExceededError';
        throw e;
      }
      store[k] = v;
    },
    removeItem: (k: string) => { delete store[k]; },
    clear: () => { Object.keys(store).forEach((k) => delete store[k]); },
  };
}

const originalLS = globalThis.localStorage;
afterEach(() => {
  Object.defineProperty(globalThis, 'localStorage', { value: originalLS, configurable: true });
});

describe('safeSetLocalDraft', () => {
  it('正常写入:setItem 成功时落库', () => {
    const ls = makeLocalStorage();
    Object.defineProperty(globalThis, 'localStorage', { value: ls, configurable: true });
    safeSetLocalDraft(draftCacheKey('q1'), '{"lines":3}');
    expect(ls.getItem('cpq-draft-q1')).toBe('{"lines":3}');
  });

  it('超配额:吞掉 QuotaExceededError,不抛出', () => {
    const ls = makeLocalStorage({ throwOnSet: true });
    Object.defineProperty(globalThis, 'localStorage', { value: ls, configurable: true });
    // 关键断言:绝不抛出(否则冒泡到后端失败 catch → 空白 + 丢数据)
    expect(() => safeSetLocalDraft(draftCacheKey('q2'), 'x'.repeat(10_000_000))).not.toThrow();
  });

  it('超配额:清掉旧值,避免陈旧缓存日后被兜底误恢复', () => {
    const ls = makeLocalStorage();
    Object.defineProperty(globalThis, 'localStorage', { value: ls, configurable: true });
    // 先放一个旧的小值(模拟之前能装下的旧草稿)
    ls.setItem('cpq-draft-q3', '{"lines":99,"stale":true}');
    expect(ls.getItem('cpq-draft-q3')).not.toBeNull();
    // 之后 setItem 改为抛配额错
    (ls as any).setItem = () => { const e: any = new Error('quota'); e.name = 'QuotaExceededError'; throw e; };
    safeSetLocalDraft('cpq-draft-q3', 'x'.repeat(10_000_000));
    // 旧值应被清掉(getItem 为 null),而不是残留陈旧数据
    expect(ls.getItem('cpq-draft-q3')).toBeNull();
  });

  it('removeItem 也抛错时仍不抛出(双保险)', () => {
    const ls = makeLocalStorage({ throwOnSet: true });
    (ls as any).removeItem = () => { throw new Error('boom'); };
    Object.defineProperty(globalThis, 'localStorage', { value: ls, configurable: true });
    expect(() => safeSetLocalDraft('cpq-draft-q4', 'big')).not.toThrow();
  });

  it('draftCacheKey 拼接稳定', () => {
    expect(draftCacheKey('2a745a90')).toBe('cpq-draft-2a745a90');
  });
});
