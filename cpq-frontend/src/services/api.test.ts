import { describe, it, expect } from 'vitest';
import { buildApiError } from './api';

describe('buildApiError', () => {
  it('挂载后端结构化 payload 到 error.payload', () => {
    const err = buildApiError({
      response: { status: 422, data: { message: '行键重复', data: { conflicts: [{ rowKey: 'X' }] } } },
    });
    expect(err.message).toBe('行键重复');
    expect((err as any).payload).toEqual({ conflicts: [{ rowKey: 'X' }] });
    expect((err as any).httpStatus).toBe(422);
  });

  it('无 data 时 payload 为 null、message 兜底', () => {
    const err = buildApiError({});
    expect(err.message).toBe('Network error');
    expect((err as any).payload).toBeNull();
  });
});
