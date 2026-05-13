// Configure Product types (matches backend DTOs in com.cpq.configure.dto)

export type ProductType = 'SIMPLE' | 'COMPOSITE';
export type PartMode = 'existing' | 'custom';
export type CompositeType = 'SIMPLE' | 'COMPOSITE' | 'PART';

export interface ElementOverride {
  elementCode: string;
  pct: number;
}

export interface PartRequest {
  name: string;
  partMode: PartMode;
  existingHfPartNo?: string;
  recipeCode?: string;
  elements?: ElementOverride[];
  processIds?: string[];
  unitWeightGrams?: number;
}

export interface CompositeProcessRequest {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

export interface ConfigureProductRequest {
  productType: ProductType;
  parts: PartRequest[];
  compositeProcesses?: CompositeProcessRequest[];
}

export interface ConfigureProductResponse {
  lineItems: Array<Record<string, any>>;
  fingerprintMatched: boolean;
  reusedHfPartNos: string[];
}

export interface LookupFingerprintRequest {
  productType: ProductType;
  recipeCode?: string;
  elements?: ElementOverride[];
  childHfPartNos?: string[];
}

export interface LookupFingerprintSnapshot {
  unitWeightGrams?: number;
  processes: Array<{ processCode: string; seqNo: number; name?: string }>;
  compositeProcesses: Array<{
    defCode: string;
    seqNo: number;
    participatingParts: string[];
    paramValues: any;
  }>;
}

export interface LookupFingerprintResponse {
  matched: boolean;
  hfPartNo?: string;
  snapshot?: LookupFingerprintSnapshot;
}

export interface SearchPartResult {
  hfPartNo: string;
  partName?: string;
  specification?: string;
  sizeInfo?: string;
  statusCode?: string;
  recipeId?: string;
  recipeCode?: string;
  recipeSymbol?: string;
  recipeName?: string;
  recipeSpec?: string;
  recipeType?: 'locked' | 'editable' | 'partial';
}
