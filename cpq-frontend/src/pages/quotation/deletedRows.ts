/**
 * 前端墓碑纯工具 — 与后端 DeletedRowKeys.java 逐字节对齐
 *
 * canon 规则：
 *   null/undefined → "∅"
 *   boolean → "true"/"false"
 *   number → 整数无小数点，否则去尾零
 *   其余 → String(v)
 *
 * rowFingerprint：
 *   1. rowKeyFieldNames 段：按传入顺序各取 driverRow[name] canon 值
 *   2. 全量段：driverRow 全部键按 UTF-16 升序，各取 canon 值
 *   两段拼接（注意：rowKey 字段值会出现两次，这是契约）
 *   最终以 "" join（无分隔符）
 */

export type Tombstone = { effKey: string; fp: string };

function canon(v: unknown): string {
  if (v === null || v === undefined) return '∅';
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v === 'number') {
    if (Number.isInteger(v)) return String(v);
    let s = v.toString();
    if (s.includes('.')) s = s.replace(/0+$/, '').replace(/\.$/, '');
    return s;
  }
  return String(v);
}

export function rowFingerprint(
  rowKeyFieldNames: string[] | undefined | null,
  driverRow: Record<string, unknown> | undefined | null,
): string {
  const parts: string[] = [];
  // 段 1：rowKeyFieldNames 按传入顺序
  for (const name of (rowKeyFieldNames ?? [])) {
    parts.push(canon(driverRow?.[name]));
  }
  // 段 2：driverRow 全部键按 UTF-16 升序（Object.keys().sort() 与 Java Collections.sort(String) 中文 BMP 同序）
  if (driverRow) {
    for (const k of Object.keys(driverRow).sort()) {
      parts.push(canon(driverRow[k]));
    }
  }
  return parts.join('');
}

export function keepRow(
  effKey: string,
  fp: string,
  deleted: Tombstone[] | undefined | null,
): boolean {
  if (!deleted || deleted.length === 0) return true;
  return !deleted.some((t) => t.effKey === effKey && t.fp === fp);
}
