import { describe, expect, it } from 'vitest';
import { formatElementActualPrice } from './snapshotDisplay';

describe('formatElementActualPrice', () => {
  it('keeps the existing 9-place price-snapshot display independent of sales role caps', () => {
    expect(formatElementActualPrice('1.2345678915')).toBe('1.234567892');
    expect(formatElementActualPrice('12.500000000000')).toBe('12.5');
  });
});
