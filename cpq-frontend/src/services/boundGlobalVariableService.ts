import api from './api';

// -------------------------------------------------------------------------
// DTO 类型定义（与后端 ADR-002 / 4.2 节对齐）
// -------------------------------------------------------------------------

/** 模板绑定的全局变量记录（端点 1/2 响应元素） */
export interface TemplateGvBindingDTO {
  id: string;
  templateId: string;
  globalVariableCode: string;
  globalVariableName: string;
  varType: 'LOOKUP_TABLE' | 'SCALAR';
  unit?: string;
  isActive: boolean;
  displayOrder: number;
  createdAt?: string;
}

/** PUT 端点的请求体单项 */
export interface BindingItem {
  globalVariableCode: string;
  displayOrder: number;
}

/** DRAFT 实时数据响应（端点 3） */
export interface BoundGvViewDTO {
  code: string;
  name: string;
  varType: 'LOOKUP_TABLE' | 'SCALAR';
  unit?: string;
  displayOrder: number;
  fetchedAt?: string;
  columns: string[];
  rows: Record<string, unknown>[];
}

/** 非 DRAFT 快照数据响应（端点 4），shape 与 BoundGvViewDTO 相同，仅 URL 不同 */
export type BoundGvSnapshotItem = BoundGvViewDTO;

// -------------------------------------------------------------------------
// Service 方法
// -------------------------------------------------------------------------

// 后端统一返回 { code, data } 包装结构；api.ts 拦截器仅解了 axios.response.data 一层，
// 业务侧需要再解一层 .data 才能拿到真实 payload（与 quotationService 等老 service 风格保持一致）。
const unwrap = <T>(r: any): T => (r && typeof r === 'object' && 'data' in r ? (r.data as T) : r);

export const boundGlobalVariableService = {
  /**
   * 1. 查询模板已绑定的全局变量列表
   *    GET /api/cpq/templates/{tid}/global-variable-bindings
   */
  getTemplateBindings: (templateId: string): Promise<TemplateGvBindingDTO[]> =>
    api.get(`/templates/${templateId}/global-variable-bindings`).then(unwrap<TemplateGvBindingDTO[]>),

  /**
   * 2. 全量替换模板绑定关系（PUT 语义）
   *    PUT /api/cpq/templates/{tid}/global-variable-bindings
   */
  updateTemplateBindings: (
    templateId: string,
    bindings: BindingItem[],
  ): Promise<TemplateGvBindingDTO[]> =>
    api
      .put(`/templates/${templateId}/global-variable-bindings`, { bindings })
      .then(unwrap<TemplateGvBindingDTO[]>),

  /**
   * 3. 获取报价单引用数据（DRAFT 实时）
   *    GET /api/cpq/quotations/{qid}/ref-data
   */
  getQuotationRefData: (quotationId: string): Promise<BoundGvViewDTO[]> =>
    api.get(`/quotations/${quotationId}/ref-data`).then(unwrap<BoundGvViewDTO[]>),

  /**
   * 4. 获取报价单引用数据快照（非 DRAFT）
   *    GET /api/cpq/quotations/{qid}/ref-data/snapshot
   */
  getQuotationRefDataSnapshot: (quotationId: string): Promise<BoundGvSnapshotItem[]> =>
    api
      .get(`/quotations/${quotationId}/ref-data/snapshot`)
      .then(unwrap<BoundGvSnapshotItem[]>),

  /**
   * 5. 获取全局变量候选列表（后端默认只返回 is_active=true 的记录）
   *    GET /api/cpq/global-variables
   *    复用现有后端端点（ADR-002 §4.1 端点 5）
   */
  listGlobalVariableDefinitions: (): Promise<
    Array<{
      code: string;
      name: string;
      varType: 'LOOKUP_TABLE' | 'SCALAR';
      unit?: string;
      description?: string;
      isActive?: boolean;
    }>
  > =>
    api.get('/global-variables').then(
      unwrap<Array<{
        code: string;
        name: string;
        varType: 'LOOKUP_TABLE' | 'SCALAR';
        unit?: string;
        description?: string;
        isActive?: boolean;
      }>>,
    ),
};
