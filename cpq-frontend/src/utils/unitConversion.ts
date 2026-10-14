import { isDecimalString, normalizeDecimalString, toDecimal, type DecimalString } from './precision';

export type UnitCategory = 'QUANTITY' | 'WEIGHT';
export interface UnitDefinition { id?: number | string; code: string; category: UnitCategory; enabled?: boolean }
export interface UnitRelation { sourceUnitCode?: string; targetUnitCode?: string; source?: string; target?: string; factor: number | string | DecimalString }
export interface UnitConversionContext { units?: UnitDefinition[]; relations?: UnitRelation[]; targetUnit?: string | null }
export type UnitRelationsInput = UnitRelation[] | Record<string, number | string | DecimalString>;

const normalize = (unitText: string | null | undefined): string =>
  unitText == null ? '' : String(unitText).trim().replace(/\s+/g, '').toUpperCase();
const PRESET_CATEGORIES: Record<string, UnitCategory> = {
  EA: 'QUANTITY', PCS: 'QUANTITY', KPCS: 'QUANTITY', G: 'WEIGHT', KG: 'WEIGHT', T: 'WEIGHT', '盎司': 'WEIGHT',
};
// Compatibility only for legacy calculation callers that do not yet carry a
// target unit. Current quote/cost paths always pass a concrete target or null;
// therefore removed aliases/compound units never participate in new business
// calculations.
const LEGACY_FACTORS: Record<string, DecimalString> = {
  '克': '0.001', G: '0.001', '千克': '1', KG: '1', '吨': '1000', T: '1000',
  '片': '1', PCS: '1', '千片': '0.001', KPCS: '0.001',
  'G/PCS': '0.001', 'KG/KPCS': '0.001', 'G/KPCS': '0.000001',
};
const DEFAULT_RELATIONS: Record<string, DecimalString> = {
  'EA|PCS': '1', 'EA|KPCS': '0.001', 'PCS|EA': '1', 'PCS|KPCS': '0.001', 'KPCS|EA': '1000', 'KPCS|PCS': '1000',
  'G|KG': '0.001', 'G|T': '0.000001', 'G|盎司': '0.03527396194958', 'KG|G': '1000', 'KG|T': '0.001', 'KG|盎司': '35.27396194958',
  'T|G': '1000000', 'T|KG': '1000', 'T|盎司': '35273.96194958', '盎司|G': '28.349523125', '盎司|KG': '0.028349523125', '盎司|T': '0.000028349523125',
};
function relationMap(context?: UnitConversionContext): Record<string, DecimalString> {
  const map = context && context.relations ? {} : { ...DEFAULT_RELATIONS };
  for (const relation of context?.relations ?? []) {
    const source = normalize(relation.sourceUnitCode ?? relation.source);
    const target = normalize(relation.targetUnitCode ?? relation.target);
    if (source && target) map[`${source}|${target}`] = String(relation.factor) as DecimalString;
  }
  return map;
}
function categoryOf(code: string, context?: UnitConversionContext): UnitCategory | undefined {
  return context?.units?.find((u) => normalize(u.code) === code)?.category ?? PRESET_CATEGORIES[code];
}

/** Directed source → target factor; unknown/cross-category/missing relation safely returns 1. */
export function factorFor(unitText: string | null | undefined, targetUnit?: string | null, context?: UnitConversionContext): DecimalString {
  const source = normalize(unitText);
  if (targetUnit === undefined && context?.targetUnit === undefined) {
    return LEGACY_FACTORS[source] ?? '1';
  }
  const target = normalize(targetUnit ?? context?.targetUnit);
  if (!source || !target || source === target) return '1';
  const sourceCategory = categoryOf(source, context); const targetCategory = categoryOf(target, context);
  if (!sourceCategory || sourceCategory !== targetCategory) return '1';
  return relationMap(context)[`${source}|${target}`] ?? '1';
}

type FieldLike = { name?: string; key?: string; unit_source_field?: string };
function fieldKey(f: FieldLike): string { return f.name || f.key || ''; }

export function applyUnitConversion<T extends Record<string, any>>(
  fields: FieldLike[] | undefined,
  row: T,
  targetOrContext?: string | null | UnitConversionContext,
  relations?: UnitRelationsInput,
): T {
  if (!fields || !row) return row;
  if (row.__unitConverted === true) return row;
  const configured: Array<[string, string]> = [];
  for (const f of fields) {
    if (!f.unit_source_field) continue;
    const column = fieldKey(f);
    if (column) configured.push([column, f.unit_source_field]);
  }
  if (!configured.length) return row;
  const context: UnitConversionContext = typeof targetOrContext === 'string' || targetOrContext == null
    ? (() => {
      // A legacy caller that supplies only the target still uses the built-in
      // preset graph. An explicitly supplied empty graph means "no relation"
      // and must remain an identity conversion for dynamic unit management.
      if (relations == null) return { targetUnit: targetOrContext };
      return {
        targetUnit: targetOrContext,
        relations: Array.isArray(relations)
          ? relations
          : Object.entries(relations).map(([key, factor]) => {
            const [source, target] = key.split('|'); return { source, target, factor };
          }),
      };
    })()
    : targetOrContext;
  const out: Record<string, any> = { ...row };
  for (const [column, sourceField] of configured) {
    const raw = row[column];
    if (!isDecimalString(raw)) continue;
    const sourceUnit = row[sourceField] == null ? '' : String(row[sourceField]);
    out[column] = normalizeDecimalString(toDecimal(raw).times(factorFor(sourceUnit, context?.targetUnit, context)));
  }
  return out as T;
}

export const normalizeUnitCode = normalize;
export const isManagedUnitCode = (code: string | null | undefined, context?: UnitConversionContext): boolean => {
  const normalized = normalize(code);
  return !!normalized && !normalized.includes('/') && !!categoryOf(normalized, context);
};
