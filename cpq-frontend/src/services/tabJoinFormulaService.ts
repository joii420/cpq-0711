import api from './api';

export interface TabDef {
  alias: string;
  tabKey: string;
  componentId?: string;
  componentName?: string;
  componentType?: string;
  sortOrder?: number;
  rowKeyFields: string[];
  detailFields: string[];
  subtotalCols: string[];
}

export interface SampleCard {
  quotationId: string;
  quotationNo: string;
  lineItemId: string;
  cardName: string;
}

// api 响应拦截器已 return response.data，所以调用层拿到的是 {code, message, data} 信封，需手动 .data 解包
export const tabJoinFormulaService = {
  tabDefs: (templateId: string): Promise<{ code: number; data: TabDef[] }> =>
    api.get(`/templates/${templateId}/excel-view-config/tab-defs`) as Promise<any>,

  sampleCards: (templateId: string): Promise<{ code: number; data: SampleCard[] }> =>
    api.get(`/templates/${templateId}/excel-view-config/sample-cards`) as Promise<any>,

  dryRun: (
    templateId: string,
    lineItemId: string,
    column: any,
    cardValuesJson?: string,
  ): Promise<{ code: number; data: { value: any; errors: string[] } }> =>
    api.post(`/templates/${templateId}/excel-view-config/dry-run-tab-formula`, {
      lineItemId,
      column,
      cardValuesJson,
    }) as Promise<any>,
};
