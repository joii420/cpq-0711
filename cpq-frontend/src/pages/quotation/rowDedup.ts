/**
 * 判重专用组合键（input-inclusive）：逐字段 driverRow 非空优先，否则取 rowValues。
 * 与 useCardSnapshots#computeRowKey 区别：额外读 rowValues（手填输入字段值），仅用于行键唯一性判重，
 * 不接入 editRows / formula 路径。镜像后端 FormulaCalculator.computeDedupKey。
 */
export function computeDedupKey(
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowValues: Record<string, any> | undefined,
): string | null {
  if (!rowKeyFields || rowKeyFields.length === 0) return null;
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return null;
  let any = false;
  const parts = rowKeyFields.map((f) => {
    let v = pickNonEmpty(driverRow, f);
    if (v == null) v = pickNonEmpty(rowValues, f);
    if (v != null) any = true;
    return v == null ? '' : v;
  });
  if (!any) return null;
  return parts.join('||');
}

function pickNonEmpty(obj: Record<string, any> | undefined, field: string): string | null {
  if (!obj) return null;
  const v = obj[field];
  if (v == null) return null;
  const s = String(v);
  return s.length === 0 ? null : s;
}

/** 返回组合键重复（≥2 次且非空）的行下标集合。 */
export function findDuplicateRowKeys(
  rows: Array<{ driverRow?: Record<string, any>; rowValues?: Record<string, any> }>,
  rowKeyFields: string[] | undefined | null,
): Set<number> {
  const dup = new Set<number>();
  if (!rowKeyFields || rowKeyFields.length === 0) return dup;
  const byKey = new Map<string, number[]>();
  rows.forEach((r, i) => {
    const k = computeDedupKey(rowKeyFields, r.driverRow, r.rowValues);
    if (k == null) return;
    const arr = byKey.get(k) ?? [];
    arr.push(i);
    byKey.set(k, arr);
  });
  for (const idxs of byKey.values()) {
    if (idxs.length >= 2) idxs.forEach((i) => dup.add(i));
  }
  return dup;
}
