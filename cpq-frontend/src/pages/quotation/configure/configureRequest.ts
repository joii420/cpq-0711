import type {
  CompositeProcessRequest,
  CompositeSelectionState,
  PartRequest,
  ProductType,
  SelDetailRow,
} from '../../../types/configure';
import {
  isDecimalString,
  normalizeDecimalString,
  sumDecimal,
  toDecimal,
  type DecimalString,
} from '../../../utils/precision';

export function normalizeQuantityInput(value: string | null | undefined): DecimalString {
  if (!isDecimalString(value)) return '1';
  const quantity = toDecimal(value);
  if (!quantity.isInteger() || quantity.lessThan(1)) return '1';
  return normalizeDecimalString(quantity);
}

export function sumQuantity(rows: SelDetailRow[]) {
  return sumDecimal(rows.map((row) => row.quantity));
}

export function buildConfigurePartsRequest(
  rows: SelDetailRow[],
  compositeSelections: CompositeSelectionState[],
): { productType: ProductType; parts: PartRequest[]; compositeProcesses?: CompositeProcessRequest[] } {
  const productType: ProductType = sumQuantity(rows).greaterThanOrEqualTo('2') ? 'COMPOSITE' : 'SIMPLE';
  const parts: PartRequest[] = rows.map((row) => ({
    name: row.recipeLabel || row.recipeCode || '',
    partMode: 'custom',
    recipeCode: row.recipeCode!,
    elements: Object.entries(row.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct })),
    processNos: row.processNos.length > 0 ? row.processNos : undefined,
    unitWeightGrams: row.unitWeightGrams ?? undefined,
    quantity: row.quantity,
  }));
  const allPartIndexes = rows.map((_, index) => index);
  const compositeProcesses: CompositeProcessRequest[] = compositeSelections.map((selection) => ({
    defCode: selection.defCode,
    participatingPartIndexes: allPartIndexes,
    params: {},
  }));
  return {
    productType,
    parts,
    compositeProcesses: productType === 'COMPOSITE' ? compositeProcesses : undefined,
  };
}
