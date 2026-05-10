import api from './api';

/** 可导入的物理表选项（target_table 下拉用） */
export interface ExtensibleTableOption {
  tableName: string;
  displayName: string;
}

export interface BasicDataSheet {
  id: string;
  sheetName: string;
  sheetIndex: number;
  headerRowIndex: number;
  dataStartRowIndex: number;
  description?: string;
  parentConfigId?: string;
  joinColumns: string[];
  sortOrder: number;
  status: 'ACTIVE' | 'DISABLED';
  /** V58: 该 Sheet 对应的物理目标表，null = 不参与导入 */
  targetTable?: string | null;
  /** V58: 行级鉴别器条件，如 {"fee_type":"INCOMING_OTHER"} */
  targetDiscriminator?: Record<string, unknown> | null;
  /** V79: 模板类型分类（让组件 PathPickerDrawer 按"报价/核价"过滤）—— 缺省 BOTH */
  templateKind?: 'QUOTATION' | 'COSTING' | 'BOTH';
  createdAt?: string;
  updatedAt?: string;
}

export interface BasicDataAttribute {
  id: string;
  configId: string;
  columnLetter: string;
  columnTitle: string;
  variableCode: string;
  variableLabel: string;
  dataType: 'IDENTIFIER' | 'VALUE';
  status: 'ACTIVE' | 'DISABLED';
  sortOrder: number;
  /** V58: 该字段在导入时是否为必填 */
  isRequired?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface DerivedAttribute {
  id: string;
  hostSheetId: string;
  variableCode: string;
  variableLabel: string;
  dataType: 'IDENTIFIER' | 'VALUE';
  computationType: 'LOOKUP' | 'EXPRESSION' | 'AGGREGATE';
  computation: string;  // JSON string
  status: 'ACTIVE' | 'DISABLED';
  sortOrder: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface ParsedExcelStructure {
  sheets: Array<{
    sheetIndex: number;
    sheetName: string;
    headerRowIndex: number;
    columns: Array<{ columnLetter: string; columnIndex: number; columnTitle: string }>;
  }>;
}

export const basicDataConfigService = {
  // Sheets — V79 起 listSheets 增加 templateKind 过滤参数
  listSheets: (statusOrParams?: string | { status?: string; templateKind?: 'QUOTATION' | 'COSTING' }, templateKind?: 'QUOTATION' | 'COSTING') => {
    // 兼容老调用（传字符串 status）+ 新调用（传 {status, templateKind} 对象）
    const params = typeof statusOrParams === 'string'
      ? { status: statusOrParams, ...(templateKind ? { templateKind } : {}) }
      : (statusOrParams || {});
    return api.get('/basic-data-config/sheets', { params }) as Promise<{ data: BasicDataSheet[] }>;
  },
  getSheet: (id: string) =>
    api.get(`/basic-data-config/sheets/${id}`) as Promise<{ data: BasicDataSheet }>,
  createSheet: (data: Partial<BasicDataSheet>) =>
    api.post('/basic-data-config/sheets', data) as Promise<{ data: BasicDataSheet }>,
  updateSheet: (id: string, data: Partial<BasicDataSheet>) =>
    api.put(`/basic-data-config/sheets/${id}`, data) as Promise<{ data: BasicDataSheet }>,
  deleteSheet: (id: string) =>
    api.delete(`/basic-data-config/sheets/${id}`) as Promise<{ data: void }>,

  // Attributes
  listAttributes: (sheetId: string, status?: string) =>
    api.get('/basic-data-config/attributes', { params: { sheetId, ...(status ? { status } : {}) } }) as Promise<{ data: BasicDataAttribute[] }>,
  createAttribute: (data: Partial<BasicDataAttribute>) =>
    api.post('/basic-data-config/attributes', data) as Promise<{ data: BasicDataAttribute }>,
  updateAttribute: (id: string, data: Partial<BasicDataAttribute>) =>
    api.put(`/basic-data-config/attributes/${id}`, data) as Promise<{ data: BasicDataAttribute }>,
  disableAttribute: (id: string) =>
    api.delete(`/basic-data-config/attributes/${id}`) as Promise<{ data: void }>,

  // Derived
  listDerived: (sheetId: string, status?: string) =>
    api.get('/basic-data-config/derived', { params: { sheetId, ...(status ? { status } : {}) } }) as Promise<{ data: DerivedAttribute[] }>,
  createDerived: (data: any) =>
    api.post('/basic-data-config/derived', data) as Promise<{ data: DerivedAttribute }>,
  updateDerived: (id: string, data: any) =>
    api.put(`/basic-data-config/derived/${id}`, data) as Promise<{ data: DerivedAttribute }>,
  disableDerived: (id: string) =>
    api.delete(`/basic-data-config/derived/${id}`) as Promise<{ data: void }>,

  // V58: 获取可选物理表（target_table 下拉数据源）
  // 后端就绪后调用 GET /basic-data-config/extensible-tables
  // 当前 fallback 到硬编码 14 张表清单
  listExtensibleTables: (): Promise<{ data: ExtensibleTableOption[] }> => {
    return api
      .get('/basic-data-config/extensible-tables')
      .then((res: any) => res)
      .catch(() => {
        // 后端未就绪时 fallback mock 清单
        const FALLBACK: ExtensibleTableOption[] = [
          { tableName: 'mat_part', displayName: '物料主档' },
          { tableName: 'mat_bom', displayName: 'BOM 清单' },
          { tableName: 'mat_fee_incoming', displayName: '进料费用' },
          { tableName: 'mat_fee_outgoing', displayName: '回料费用' },
          { tableName: 'mat_fee_process', displayName: '加工费用' },
          { tableName: 'mat_element_price', displayName: '元素价格' },
          { tableName: 'customer', displayName: '客户主档' },
          { tableName: 'product', displayName: '产品档案' },
          { tableName: 'product_category', displayName: '产品分类' },
          { tableName: 'region', displayName: '区域' },
          { tableName: 'department', displayName: '部门' },
          { tableName: 'pricing_strategy', displayName: '定价策略' },
          { tableName: 'costing_template', displayName: '核价模板' },
          { tableName: 'internal_material', displayName: '生产料号' },
        ];
        return { data: FALLBACK };
      });
  },

  // Excel parse
  parseExcel: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return api.post('/basic-data-config/parse-excel', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as Promise<{ data: ParsedExcelStructure }>;
  },
};
