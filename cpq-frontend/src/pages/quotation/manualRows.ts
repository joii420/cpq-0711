export const MANUAL_ORIGIN = 'manual';

export interface DriverExpansionLike {
  rowCount: number;
  rows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any> }>;
}

export interface RowSplit {
  useDriver: boolean;
  driverCount: number;
  /** comp.rows 中 _origin==='manual' 的行 */
  manualRows: Array<Record<string, any>>;
  /** comp.rows 中非手动行(driver 页签下按 index 对齐 exp.rows;无 driver 页签下即全部行) */
  driverEditRows: Array<Record<string, any>>;
  totalRows: number;
}

export function isManualRow(row: any): boolean {
  return !!row && row._origin === MANUAL_ORIGIN;
}

export function splitRows(comp: { rows?: Array<Record<string, any>> }, exp: DriverExpansionLike | undefined): RowSplit {
  const rows = Array.isArray(comp.rows) ? comp.rows : [];
  const useDriver = !!(exp && exp.rowCount > 0);
  const driverCount = useDriver ? exp!.rowCount : 0;
  const manualRows = rows.filter(isManualRow);
  const driverEditRows = rows.filter((r) => !isManualRow(r));
  const totalRows = useDriver ? driverCount + manualRows.length : rows.length;
  return { useDriver, driverCount, manualRows, driverEditRows, totalRows };
}

export interface RowAt {
  row: Record<string, any>;
  isManual: boolean;
  /** driver 行对应的 exp.rows 下标;手动行/无 driver 为 -1 */
  expIndex: number;
}

export function rowAt(i: number, comp: { rows?: Array<Record<string, any>> }, s: RowSplit): RowAt {
  if (!s.useDriver) {
    const r = (comp.rows ?? [])[i] ?? {};
    return { row: r, isManual: isManualRow(r), expIndex: -1 };
  }
  if (i < s.driverCount) {
    return { row: s.driverEditRows[i] ?? {}, isManual: false, expIndex: i };
  }
  return { row: s.manualRows[i - s.driverCount] ?? {}, isManual: true, expIndex: -1 };
}
