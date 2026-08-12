import { describe, expect, it } from 'vitest';
import { parseSnapshotJsonLossless, tryParseSnapshotJsonLossless } from './losslessJson';

describe('lossless snapshot JSON', () => {
  it('converts a large legacy numeric token from its original literal', () => {
    const result = parseSnapshotJsonLossless<{ amount: string }>(
      '{"amount":98765431.123456789012}',
    );
    expect(result.amount).toBe('98765431.123456789012');
  });

  it('normalizes numeric tokens without changing strings, booleans or null', () => {
    const result = parseSnapshotJsonLossless<Record<string, unknown>>(
      '{"value":1.2300,"stored":"1.2300","enabled":true,"empty":null}',
    );
    expect(result).toEqual({ value: '1.23', stored: '1.2300', enabled: true, empty: null });
  });

  it('keeps known structural integers as numbers', () => {
    const result = parseSnapshotJsonLossless<{
      rowCount: number;
      row_index: number;
      annualVolume: number;
      subtotal: string;
    }>('{"rowCount":2,"row_index":1,"annualVolume":800000,"subtotal":2}');

    expect(result).toEqual({ rowCount: 2, row_index: 1, annualVolume: 800000, subtotal: '2' });
  });

  it('keeps Chinese row-order keys as safe integers', () => {
    const result = parseSnapshotJsonLossless<Record<string, unknown>>(
      '{"项次":1,"_项次":2,"序号":3,"输入数量":1.2300}',
    );
    expect(result).toEqual({ 项次: 1, _项次: 2, 序号: 3, 输入数量: '1.23' });
  });

  it('returns null for invalid historical JSON', () => {
    expect(tryParseSnapshotJsonLossless('{invalid')).toBeNull();
  });
});
