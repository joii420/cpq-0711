import type { PrecisionJsonValue } from '../../utils/precision';

export type PathFormulaResult = PrecisionJsonValue;

export class PrecisionIngressError extends Error {
  constructor(context: string) {
    super(`${context} returned a JSON number; decimal strings are required`);
    this.name = 'PrecisionIngressError';
  }
}

/** Reject numeric API leaves before they can enter formula state or caches. */
export function normalizePathFormulaResult(value: unknown, context: string): PathFormulaResult {
  if (value == null) return null;
  if (typeof value === 'number') throw new PrecisionIngressError(context);
  if (typeof value === 'string' || typeof value === 'boolean') return value;
  if (Array.isArray(value)) {
    return value.map((item, index) => normalizePathFormulaResult(item, `${context}[${index}]`));
  }
  if (typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        normalizePathFormulaResult(item, `${context}.${key}`),
      ]),
    );
  }
  throw new TypeError(`${context} returned an unsupported value`);
}
