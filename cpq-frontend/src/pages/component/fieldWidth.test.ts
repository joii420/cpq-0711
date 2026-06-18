import { describe, it, expect } from 'vitest';
import { resolveFieldWidth, DEFAULT_FIELD_WIDTH, FIELD_WIDTH_PRESETS } from './types';

describe('resolveFieldWidth', () => {
  it('未设置(undefined)→默认 120', () => {
    expect(resolveFieldWidth(undefined)).toBe(120);
    expect(DEFAULT_FIELD_WIDTH).toBe(120);
  });

  it('null / 0 / 负数 视为未设置→默认 120', () => {
    expect(resolveFieldWidth(null as unknown as number)).toBe(120);
    expect(resolveFieldWidth(0)).toBe(120);
    expect(resolveFieldWidth(-50)).toBe(120);
  });

  it('正像素值原样返回', () => {
    expect(resolveFieldWidth(80)).toBe(80);
    expect(resolveFieldWidth(160)).toBe(160);
    expect(resolveFieldWidth(200)).toBe(200);
  });

  it('档位常量为 窄80/中120/宽200', () => {
    expect(FIELD_WIDTH_PRESETS.map((p) => p.value)).toEqual([80, 120, 200]);
  });
});
