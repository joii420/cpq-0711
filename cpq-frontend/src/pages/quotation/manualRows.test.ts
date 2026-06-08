import { describe, it, expect } from 'vitest';
import { MANUAL_ORIGIN, splitRows, rowAt } from './manualRows';

const exp = (n: number) => ({ rowCount: n, rows: Array.from({ length: n }, (_, i) => ({ driverRow: { k: i }, basicDataValues: { b: i } })) });

describe('splitRows', () => {
  it('driver 页签: totalRows = driverCount + 手动行数', () => {
    const comp: any = { rows: [{ a: 1 }, { a: 2 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, exp(2));
    expect(s.useDriver).toBe(true);
    expect(s.driverCount).toBe(2);
    expect(s.manualRows).toHaveLength(1);
    expect(s.totalRows).toBe(3);
  });

  it('无 driver 页签: totalRows = comp.rows.length(含手动行)', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, undefined);
    expect(s.useDriver).toBe(false);
    expect(s.totalRows).toBe(2);
  });

  it('rowAt: driver 段取 driverEditRows+exp, 手动段取 manualRows', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, exp(1));
    const r0 = rowAt(0, comp, s);
    expect(r0.isManual).toBe(false);
    expect(r0.expIndex).toBe(0);
    const r1 = rowAt(1, comp, s);
    expect(r1.isManual).toBe(true);
    expect(r1.row.a).toBe(9);
    expect(r1.expIndex).toBe(-1);
  });

  it('无 driver: rowAt 直接取 comp.rows[i], isManual 按 _origin', () => {
    const comp: any = { rows: [{ a: 1 }, { _origin: 'manual', a: 9 }] };
    const s = splitRows(comp, undefined);
    expect(rowAt(0, comp, s).isManual).toBe(false);
    expect(rowAt(1, comp, s).isManual).toBe(true);
  });
});
