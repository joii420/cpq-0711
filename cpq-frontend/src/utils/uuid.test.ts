import { describe, it, expect, vi, afterEach } from 'vitest';
import { genUUID } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('genUUID', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses crypto.randomUUID when available (secure context)', () => {
    const fake = '11111111-1111-4111-8111-111111111111';
    vi.stubGlobal('crypto', { randomUUID: () => fake, getRandomValues: () => {} });
    expect(genUUID()).toBe(fake);
  });

  it('falls back to getRandomValues when randomUUID is missing (insecure HTTP/IP context)', () => {
    // 模拟非安全上下文：crypto 存在但 randomUUID 未挂载 —— 这正是本次 bug 的现场
    vi.stubGlobal('crypto', {
      getRandomValues: (arr: Uint8Array) => {
        for (let i = 0; i < arr.length; i++) arr[i] = i;
        return arr;
      },
    });
    expect(genUUID()).toMatch(UUID_V4);
  });

  it('falls back to Math.random when crypto is entirely absent', () => {
    vi.stubGlobal('crypto', undefined);
    expect(genUUID()).toMatch(UUID_V4);
  });

  it('produces unique values across many calls (real environment)', () => {
    const set = new Set(Array.from({ length: 1000 }, () => genUUID()));
    expect(set.size).toBe(1000);
  });
});
