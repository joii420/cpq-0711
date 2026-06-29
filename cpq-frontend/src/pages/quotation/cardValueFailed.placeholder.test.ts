import { describe, it, expect } from 'vitest';
// 从轻量模块导入，避免拉起 QuotationStep2.tsx 的重依赖（antd/services/...）。
// QuotationStep2.tsx re-export 同一函数供运行时使用。
import { isCardValueFailed } from './cardValueFailed';

describe('isCardValueFailed', () => {
  it('哨兵 JSON → true', () => {
    expect(isCardValueFailed('{"tabs":[],"__cardValueFailed":true}')).toBe(true);
  });
  it('正常卡片值 → false', () => {
    expect(isCardValueFailed('{"tabs":[{"componentId":"x","baseRows":[]}]}')).toBe(false);
  });
  it('undefined/空 → false', () => {
    expect(isCardValueFailed(undefined)).toBe(false);
    expect(isCardValueFailed('')).toBe(false);
  });
  it('坏 JSON → false(不抛)', () => {
    expect(isCardValueFailed('{not json')).toBe(false);
  });
});
