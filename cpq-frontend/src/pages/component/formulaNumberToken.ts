import type { FormulaToken } from './types';
import { isDecimalString, normalizeDecimalString } from '../../utils/precision';

export function createFormulaNumberToken(value: unknown): FormulaToken | null {
  if (!isDecimalString(value)) return null;
  const decimal = normalizeDecimalString(value);
  return { type: 'number', value: decimal, label: decimal };
}
