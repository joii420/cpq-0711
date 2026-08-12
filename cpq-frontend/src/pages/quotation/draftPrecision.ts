import {
  formatFormulaResult,
  isDecimalString,
  isFormulaFieldType,
  normalizeDecimalString,
  type DecimalString,
} from '../../utils/precision';

interface DraftFieldLike {
  name?: string;
  key?: string;
  field_type?: string;
  fieldType?: string;
}

export function toDraftComponentSubtotal(value: unknown): DecimalString {
  return isDecimalString(value) ? normalizeDecimalString(value) : '0';
}

/** Apply computed results only; input and source cells retain their exact stored spelling. */
export function applyFormulaResultsToDraftRow(
  row: Record<string, unknown>,
  fields: ReadonlyArray<DraftFieldLike>,
  formulaResults: Record<string, DecimalString | null>,
): Record<string, unknown> {
  const draftRow = { ...row };
  for (const field of fields) {
    if (!isFormulaFieldType(field.field_type ?? field.fieldType)) continue;
    const fieldKey = field.name || field.key || '';
    const result = fieldKey ? formulaResults[fieldKey] : null;
    if (fieldKey && result != null) draftRow[fieldKey] = formatFormulaResult(result);
  }
  return draftRow;
}
