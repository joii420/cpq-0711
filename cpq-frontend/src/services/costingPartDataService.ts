import api from './api';

export type ProcessCostType =
  | 'LABOR' | 'DEPRECIATION' | 'ENERGY_DEDICATED' | 'ENERGY_SHARED'
  | 'CONSUMABLE' | 'MATERIAL_PROC' | 'SEMI_FINISHED_PROC' | 'POST_PROC';

export interface ProcessCost {
  id?: string;
  hfPartNo: string;
  processNo: string;
  processName?: string;
  costType: ProcessCostType;
  unitPrice: number;
  currency?: string;
  unit?: string;
  refCalcVersion?: string;
  isActive?: boolean;
  notes?: string;
}

export interface ToolingCost {
  id?: string;
  hfPartNo: string;
  processNo: string;
  processName?: string;
  seqNo: number;
  toolingNo?: string;
  toolingUnitCost: number;
  processCount?: number;
  cycleCount?: number;
  unitPrice?: number;
  currency?: string;
  unit?: string;
  isActive?: boolean;
  notes?: string;
}

export interface MaterialBom {
  id?: string;
  hfPartNo: string;
  seqNo: number;
  inputMaterialNo?: string;
  processNo?: string;
  processName?: string;
  inputQty?: number;
  inputUnit?: string;
  outputQty?: number;
  outputUnit?: string;
  outputLossRate?: number;
  fixedLossQty?: number;
  lossRate?: number;
  isActive?: boolean;
  notes?: string;
}

export interface ElementBom {
  id?: string;
  inputMaterialNo: string;
  seqNo: number;
  elementCode: string;
  compositionPct: number;
  lossRate?: number;
  isActive?: boolean;
  notes?: string;
}

export interface QualityCheck {
  id?: string;
  hfPartNo: string;
  stage: 'INCOMING' | 'SEMI_FINISHED';
  primarySeqNo?: number;
  seqNo: number;
  requirementCode?: string;
  requirementDesc?: string;
  scrapRate?: number;
  isActive?: boolean;
  notes?: string;
}

export interface Plating {
  id?: string;
  platingNo: string;
  versionNumber: string;
  seqNo: number;
  elementAttr?: string;
  platingAreaCm2?: number;
  layerThicknessUm?: number;
  requirement?: string;
  isActive?: boolean;
}

// 电镀费用 (V125 核价侧 costing_part_plating_fee, 按 partNo, 不带客户维度)
export interface PlatingFee {
  id?: string;
  hfPartNo: string;
  platingPlanCode?: string;
  planVersion?: string;
  platingProcessFee?: number;
  platingMaterialFee?: number;
  currency?: string;
  priceUnit?: string;
  defectRate?: number;
  isActive?: boolean;
  notes?: string;
}

export interface DesignCost {
  id?: string;
  hfPartNo: string;
  designDrawingNo?: string;
  versionNumber?: string;
  designProcFee?: number;
  designMaterialFee?: number;
  currency?: string;
  unit?: string;
  lossRate?: number;
  isActive?: boolean;
  notes?: string;
}

export interface PartWeight {
  id?: string;
  hfPartNo: string;
  weightGPerPcs: number;
  isActive?: boolean;
  notes?: string;
}

const base = '/costing-part';

export const costingPartDataService = {
  // 1. 工序级单价
  listProcessCost: (hfPartNo: string, costType?: ProcessCostType) =>
    api.get(`${base}/process-cost`, { params: { hfPartNo, costType } }) as Promise<{ data: ProcessCost[] }>,
  saveProcessCost: (req: ProcessCost) =>
    api.post(`${base}/process-cost`, req) as Promise<{ data: ProcessCost }>,
  deleteProcessCost: (id: string) =>
    api.delete(`${base}/process-cost/${id}`) as Promise<{ data: void }>,

  // 2. 模具工装
  listTooling: (hfPartNo: string) =>
    api.get(`${base}/tooling`, { params: { hfPartNo } }) as Promise<{ data: ToolingCost[] }>,
  saveTooling: (req: ToolingCost) =>
    api.post(`${base}/tooling`, req) as Promise<{ data: ToolingCost }>,
  deleteTooling: (id: string) =>
    api.delete(`${base}/tooling/${id}`) as Promise<{ data: void }>,

  // 3. 材料 BOM
  listMaterialBom: (hfPartNo: string) =>
    api.get(`${base}/material-bom`, { params: { hfPartNo } }) as Promise<{ data: MaterialBom[] }>,
  saveMaterialBom: (req: MaterialBom) =>
    api.post(`${base}/material-bom`, req) as Promise<{ data: MaterialBom }>,
  deleteMaterialBom: (id: string) =>
    api.delete(`${base}/material-bom/${id}`) as Promise<{ data: void }>,

  // 4. 元素 BOM
  listElementBom: (inputMaterialNo: string) =>
    api.get(`${base}/element-bom`, { params: { inputMaterialNo } }) as Promise<{ data: ElementBom[] }>,
  saveElementBom: (req: ElementBom) =>
    api.post(`${base}/element-bom`, req) as Promise<{ data: ElementBom }>,
  deleteElementBom: (id: string) =>
    api.delete(`${base}/element-bom/${id}`) as Promise<{ data: void }>,

  // 5. 质量检验
  listQualityCheck: (hfPartNo: string, stage?: QualityCheck['stage']) =>
    api.get(`${base}/quality-check`, { params: { hfPartNo, stage } }) as Promise<{ data: QualityCheck[] }>,
  saveQualityCheck: (req: QualityCheck) =>
    api.post(`${base}/quality-check`, req) as Promise<{ data: QualityCheck }>,
  deleteQualityCheck: (id: string) =>
    api.delete(`${base}/quality-check/${id}`) as Promise<{ data: void }>,

  // 6. 电镀
  listPlating: (platingNo?: string) =>
    api.get(`${base}/plating`, { params: { platingNo } }) as Promise<{ data: Plating[] }>,
  savePlating: (req: Plating) =>
    api.post(`${base}/plating`, req) as Promise<{ data: Plating }>,
  deletePlating: (id: string) =>
    api.delete(`${base}/plating/${id}`) as Promise<{ data: void }>,

  // 6.b 电镀费用 (按 hfPartNo, 只读)
  listPlatingFee: (hfPartNo: string) =>
    api.get(`${base}/plating-fee`, { params: { hfPartNo } }) as Promise<{ data: PlatingFee[] }>,

  // 7. 设计成本
  listDesignCost: (hfPartNo: string) =>
    api.get(`${base}/design-cost`, { params: { hfPartNo } }) as Promise<{ data: DesignCost[] }>,
  saveDesignCost: (req: DesignCost) =>
    api.post(`${base}/design-cost`, req) as Promise<{ data: DesignCost }>,
  deleteDesignCost: (id: string) =>
    api.delete(`${base}/design-cost/${id}`) as Promise<{ data: void }>,

  // 8. 重量
  getWeight: (hfPartNo: string) =>
    api.get(`${base}/weight`, { params: { hfPartNo } }) as Promise<{ data: PartWeight | null }>,
  saveWeight: (req: PartWeight) =>
    api.post(`${base}/weight`, req) as Promise<{ data: PartWeight }>,
  deleteWeight: (id: string) =>
    api.delete(`${base}/weight/${id}`) as Promise<{ data: void }>,
};
