/**
 * task-260920 F-7 / AC-30: the review list only accepts the latest request.
 * The scenario mirrors the reproduced race: the first-screen full list request is sent first but
 * answers LAST, after the keyword search. loadLatest is the exact function PriceAdjustReviewPage.load uses.
 */
import { describe, it, expect, vi } from 'vitest';
import { createLatestGate, loadLatest } from './reviewCompute';

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('F-7 · 列表查询只认最新一次请求（AC-30）', () => {
  it('两次加载乱序返回，只有最新的落地（进页全量请求晚于搜索请求到达）', async () => {
    const gate = createLatestGate();
    const landed: string[] = [];
    const errors: unknown[] = [];
    const settled: string[] = [];
    const full = deferred<{ label: string; totalElements: number }>();
    const search = deferred<{ label: string; totalElements: number }>();

    const first = loadLatest(gate, () => full.promise, {
      onData: (d) => landed.push(`${d.label}:${d.totalElements}`), onError: (e) => errors.push(e), onSettled: () => settled.push('full'),
    });
    const second = loadLatest(gate, () => search.promise, {
      onData: (d) => landed.push(`${d.label}:${d.totalElements}`), onError: (e) => errors.push(e), onSettled: () => settled.push('search'),
    });

    search.resolve({ label: 'search', totalElements: 3 });   // newer answers first
    await expect(second).resolves.toBe(true);
    full.resolve({ label: 'full', totalElements: 4527 });    // older answers late
    await expect(first).resolves.toBe(false);

    expect(landed).toEqual(['search:3']);                    // the full list never overwrote the search result
    expect(settled).toEqual(['search']);                     // stale request did not touch the loading flag
    expect(errors).toEqual([]);
  });

  it('过期的失败不报错；最新的失败照常报错', async () => {
    const gate = createLatestGate();
    const onError = vi.fn();
    const landed: number[] = [];
    const stale = deferred<number>();
    const fresh = deferred<number>();

    const p1 = loadLatest(gate, () => stale.promise, { onData: (d) => landed.push(d), onError });
    const p2 = loadLatest(gate, () => fresh.promise, { onData: (d) => landed.push(d), onError });

    fresh.resolve(7);
    await p2;
    stale.reject(new Error('Network error'));
    await expect(p1).resolves.toBe(false);
    expect(onError).not.toHaveBeenCalled();
    expect(landed).toEqual([7]);

    // control: when the failing request IS the latest one, the error is reported
    const p3 = loadLatest(gate, () => Promise.reject(new Error('加载待办池失败')), { onData: () => {}, onError });
    await expect(p3).resolves.toBe(true);
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('按顺序返回时每次都落地（不误丢正常请求）', async () => {
    const gate = createLatestGate();
    const landed: number[] = [];
    await loadLatest(gate, async () => 1, { onData: (d) => landed.push(d), onError: () => {} });
    await loadLatest(gate, async () => 2, { onData: (d) => landed.push(d), onError: () => {} });
    expect(landed).toEqual([1, 2]);
  });
});
