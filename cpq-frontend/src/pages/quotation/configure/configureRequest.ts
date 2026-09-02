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
  const parts: PartRequest[] = rows.map((row) => {
    // task-260901 · api.md §2.4：`configNo` 与 `elements` **必须恰好给一个**
    // （两个都给或都不给 → 400 MATERIAL_SOURCE_AMBIGUOUS）。
    // contentMode 未定义时按历史行为兜底：有 elementOverrides 就当自定义含量。
    const mode = row.contentMode ?? (Object.keys(row.elementOverrides).length > 0 ? 'custom' : undefined);
    const materialSource =
      mode === 'config' && row.configNo
        ? { configNo: row.configNo }
        : mode === 'custom'
          ? {
              elements: Object.entries(row.elementOverrides)
                .map(([elementCode, pct]) => ({ elementCode, pct })),
            }
          : {};
    return {
      name: row.recipeLabel || row.recipeCode || '',
      partMode: 'custom' as const,
      recipeCode: row.recipeCode!,
      ...materialSource,
      processNos: row.processNos.length > 0 ? row.processNos : undefined,
      unitWeightGrams: row.unitWeightGrams ?? undefined,
      quantity: row.quantity,
    };
  });
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
