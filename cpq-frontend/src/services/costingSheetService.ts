import api from './api';

export interface CostingSheetData {
  id: string;
  quotationId: string;
  costingTemplateId?: string;
  costingTemplateName?: string;
  columns: Array<Record<string, any>>;
  rows: Array<{ hf_part_no: string; cells: Record<string, any> }>;
  totalCost?: number;
  status: 'LIVE' | 'SNAPSHOT';
  createdAt?: string;
  updatedAt?: string;
}

export interface ComparisonData {
  basicFieldDiffs: Array<{
    variableCode: string;
    variableLabel: string;
    costingValue: any;
    quotationValue: any;
    diffStatus: 'SAME' | 'MODIFIED' | 'MISSING' | 'NEW';
  }>;
  tagGroups: Array<{
    groupName: string;
    tags: Array<{
      tag: string;
      tagLabel: string;
      costingValue: any;
      quotationValue: any;
      delta: any;
      deltaPct?: string;
    }>;
  }>;
  summary: {
    costingTotal: any;
    quotationTotal: any;
    profit: any;
    profitRate?: string;
    modifiedFieldsCount: number;
  };
}

export const costingSheetService = {
  get: (quotationId: string) =>
    api.get(`/quotations/${quotationId}/costing-sheet`) as Promise<{ data: CostingSheetData }>,
  // 注：旧的 tag 分组比对端点。新「料号双行比对视图」(ComparisonView) 已改用前端 buildComparisonModel,
  // 不再调用此方法；但后端 GET /comparison + buildComparison 仍被 CostingComparisonResourceTest 覆盖, 故保留。
  getComparison: (quotationId: string) =>
    api.get(`/quotations/${quotationId}/comparison`) as Promise<{ data: ComparisonData }>,
};
