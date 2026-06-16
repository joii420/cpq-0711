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
  /** 本页签全部字段(含 INPUT_TEXT 文本字段);供 SUMIF 条件过滤按任意字段选取 */
  allFields?: string[];
  subtotalCols: string[];
  /** 后端标记:该 tabDef 是否为当前被编辑(宿主)组件(ComponentTabDefService 注入) */
  self?: boolean;
}

export interface SampleCard {
  quotationId: string;
  quotationNo: string;
  lineItemId: string;
  cardName: string;
}

// api 响应拦截器已 return response.data，所以调用层拿到的是 {code, message, data} 信封，需手动 .data 解包
//
// 组件统一公式重构后（Task 4.x），TAB_JOIN_FORMULA 公式编辑器从模板级迁到组件级：
// 页签集 = 同目录组件集，样本卡 = 引用本组件的报价行，试算复用同一内核。
// 端点 shape 与原模板级 /templates/{id}/excel-view-config/* 完全一致，仅作用域换成组件。
export const tabJoinFormulaService = {
  /** GET /api/cpq/components/{id}/tab-defs — 同目录组件页签定义 */
  tabDefsByComponent: (componentId: string): Promise<{ code: number; data: TabDef[] }> =>
    api.get(`/components/${componentId}/tab-defs`) as Promise<any>,

  /** GET /api/cpq/components/{id}/sample-cards — 引用本组件的报价行（最多 50 条） */
  sampleCardsByComponent: (componentId: string): Promise<{ code: number; data: SampleCard[] }> =>
    api.get(`/components/${componentId}/sample-cards`) as Promise<any>,

  /** POST /api/cpq/components/{id}/dry-run — 组件级试算，body: {lineItemId, column, cardValuesJson} */
  dryRunByComponent: (
    componentId: string,
    lineItemId: string,
    column: any,
    cardValuesJson?: string,
  ): Promise<{ code: number; data: { value: any; errors: string[] } }> =>
    api.post(`/components/${componentId}/dry-run`, {
      lineItemId,
      column,
      cardValuesJson,
    }) as Promise<any>,

  /** POST /components/{id}/dry-run-token — token 试算(NORMAL/SUBTOTAL),返逐行 {rows,errors} */
  dryRunToken: (
    componentId: string,
    lineItemId: string | null,
    tokens: unknown[],
    selfRowKeyFields: string[],
  ): Promise<{ code: number; data: { rows: { rowKey: string; value: number | null }[]; errors: string[] } }> =>
    api.post(`/components/${componentId}/dry-run-token`, {
      lineItemId,
      tokens,
      selfRowKeyFields,
    }) as Promise<any>,
};
