// 单位换算预设表（硬编码）。后端镜像 com.cpq.engine.unit.UnitConversion，对拍测试守一致。
// 设计见 docs/superpowers/specs/2026-06-15-unit-conversion-design.md §3。

const FACTORS: Record<string, number> = {
  '克': 0.001, 'G': 0.001,
  '千克': 1, 'KG': 1,
  '吨': 1000, 'T': 1000,
  '片': 1, 'PCS': 1,
  'KPCS': 1000, '千片': 1000,
  'G/PCS': 0.001,
};

function normalize(unitText: string | null | undefined): string {
  if (unitText == null) return '';
  return String(unitText).trim().replace(/\s+/g, '').toUpperCase();
}

/** 单位 → 系数；未知 / 空 → 1（原值透传）。 */
export function factorFor(unitText: string | null | undefined): number {
  const key = normalize(unitText);
  if (key === '') return 1;
  return FACTORS[key] ?? 1;
}

type FieldLike = { name?: string; key?: string; unit_source_field?: string };

function fieldKey(f: FieldLike): string {
  return f.name || f.key || '';
}

/**
 * 返回换算后新行（原 row 不变）。配 unit_source_field 的列 C → rawC × factorFor(同行 D)。
 * 无配置列时原样返回原对象（零开销 + 不破坏引用相等优化）。
 */
export function applyUnitConversion<T extends Record<string, any>>(
  fields: FieldLike[] | undefined,
  row: T,
): T {
  if (!fields || !row) return row;
  const configured: Array<[string, string]> = [];
  for (const f of fields) {
    const usf = f.unit_source_field;
    if (!usf) continue;
    const c = fieldKey(f);
    if (c) configured.push([c, usf]);
  }
  if (configured.length === 0) return row;
  const out: Record<string, any> = { ...row };
  for (const [c, d] of configured) {
    const raw = row[c];
    const num = typeof raw === 'number' ? raw : parseFloat(raw);
    if (raw == null || isNaN(num)) continue;
    out[c] = num * factorFor(row[d] == null ? '' : String(row[d]));
  }
  return out as T;
}
