import { bnfDriverLookupKey } from './useDriverExpansions';

/**
 * 判重专用组合键（字段感知 + input-inclusive）：
 * 逐字段按优先级取值：driverRow 直读 → defaultSource 解析（BDV） → rowValues（手填兜底）。
 *
 * 对齐后端 FormulaCalculator.computeDedupKey 5-arg 字段感知重载。
 * 旧 3-arg 调用点（无 fields/basicDataValues）继续兼容（可选参数）。
 *
 * 仅用于行键唯一性判重，不接入 editRows / formula 路径（避开鸡生蛋）。
 * 全字段为空 → null（不参与判重）。
 */
export function computeDedupKey(
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowValues: Record<string, any> | undefined,
  fields?: Array<{ name: string; defaultSource?: { type?: string; code?: string; path?: string } | null }> | null,
  basicDataValues?: Record<string, any>,
): string | null {
  if (!rowKeyFields || rowKeyFields.length === 0) return null;
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return null;

  // 懒建字段 map（仅在 fields 有值时构建）
  const fieldMap = new Map<string, { defaultSource?: { type?: string; code?: string; path?: string } | null }>();
  for (const f of (fields ?? [])) fieldMap.set(f.name, f);

  let any = false;
  const parts = rowKeyFields.map((fieldName) => {
    // 1. driverRow 直读（字段名即视图列名的旧场景，如 material_no）
    let v = pickNonEmpty(driverRow, fieldName);

    // 2. defaultSource 解析（driverRow 直读失败，且有字段定义 + BDV 时）
    if (v == null && basicDataValues) {
      const fd = fieldMap.get(fieldName);
      const ds = fd?.defaultSource;
      if (ds) {
        const dsType = ds.type;
        if (dsType === 'GLOBAL_VARIABLE' && ds.code) {
          v = pickNonEmpty(basicDataValues, `@gvar:${ds.code}`);
        } else if ((dsType === 'BNF_PATH' || dsType === 'BASIC_DATA') && ds.path) {
          v = pickNonEmpty(basicDataValues, bnfDriverLookupKey(ds.path));
        }
      }
    }

    // 3. rowValues 手填兜底
    if (v == null) v = pickNonEmpty(rowValues, fieldName);

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
