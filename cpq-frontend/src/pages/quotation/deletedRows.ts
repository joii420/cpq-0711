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
  // 2026-07-14 删错行修复：按 fp(内容指纹)单键匹配,弃 effKey 双命中。
  // effKey 由 computeRowKey 算,前后端对同一行可不一致(前端 driverRow['料件']=null → 索引兜底"0",
  // 服务端经字段定义解析成内容"AgNi11#-Ⅰ")→ 双命中因 effKey 对不上而漏删。fp 前后端逐字节一致,可靠。
  // effKey 形参保留仅为调用兼容。与后端 DeletedRowKeys.keepMask 语义严格对齐。
  return !deleted.some((t) => t.fp === fp);
}
