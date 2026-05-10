import axios from 'axios';
import type {
  DdlOperationDTO,
  ExtendColumnRequest,
  ExtensibleTableDTO,
  DdlHistoryPage,
} from '../types/ddl-extension';

// DDL 扩列 API 路径在 /api/system，独立实例
const sysApi = axios.create({
  baseURL: '/api/system',
  timeout: 30000,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

sysApi.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      window.location.href = '/login';
    }
    const msg = error.response?.data?.message || 'Network error';
    return Promise.reject(new Error(msg));
  },
);

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_DDL === 'true';

// ---- Mock 数据 ----
const MOCK_TABLES: ExtensibleTableDTO[] = [
  { tableName: 'product', displayName: '产品表', columnCount: 12, columns: [] },
  { tableName: 'quotation', displayName: '报价单表', columnCount: 18, columns: [] },
  { tableName: 'quotation_item', displayName: '报价明细表', columnCount: 14, columns: [] },
  { tableName: 'customer', displayName: '客户表', columnCount: 10, columns: [] },
  { tableName: 'component', displayName: '组件表', columnCount: 8, columns: [] },
  { tableName: 'product_template', displayName: '产品模板表', columnCount: 7, columns: [] },
  { tableName: 'pricing_strategy', displayName: '定价策略表', columnCount: 9, columns: [] },
  { tableName: 'internal_material', displayName: '生产料号表', columnCount: 11, columns: [] },
  { tableName: 'costing_template', displayName: '核价模板表', columnCount: 6, columns: [] },
  { tableName: 'costing_item', displayName: '核价明细表', columnCount: 8, columns: [] },
  { tableName: 'element_price', displayName: '元素价格表', columnCount: 5, columns: [] },
  { tableName: 'region', displayName: '区域表', columnCount: 4, columns: [] },
  { tableName: 'department', displayName: '部门表', columnCount: 5, columns: [] },
  { tableName: 'product_category', displayName: '产品分类表', columnCount: 5, columns: [] },
  { tableName: 'basic_data_attribute', displayName: '基础数据属性表', columnCount: 15, columns: [] },
];

const MOCK_COLUMNS: Record<string, string[]> = {
  product: ['id', 'name', 'code', 'status', 'created_at'],
  quotation: ['id', 'quote_no', 'customer_id', 'status', 'total_amount', 'created_at'],
  customer: ['id', 'name', 'code', 'region_id', 'created_at'],
  basic_data_attribute: ['id', 'sheet_id', 'col_key', 'display_name', 'field_type'],
};

const now = new Date();
let MOCK_HISTORY: DdlOperationDTO[] = [
  {
    id: 'ddl-001',
    tableName: 'product',
    columnName: 'ext_category_code',
    dataType: 'VARCHAR(64)',
    defaultValue: '',
    importance: 'NORMAL',
    affectsCalculation: false,
    remark: '扩展产品分类编码字段',
    status: 'SUCCESS',
    migrationContent: `-- Flyway migration V56__add_product_ext_category_code.sql
ALTER TABLE product ADD COLUMN IF NOT EXISTS ext_category_code VARCHAR(64) NOT NULL DEFAULT '';
COMMENT ON COLUMN product.ext_category_code IS 'ext_category_code — importance=NORMAL affects_calculation=false';`,
    flywayVersionHint: 'V56',
    createdByName: '管理员',
    createdAt: new Date(now.getTime() - 86400000 * 2).toISOString(),
  },
  {
    id: 'ddl-002',
    tableName: 'quotation',
    columnName: 'ext_project_no',
    dataType: 'VARCHAR(128)',
    defaultValue: '',
    importance: 'IMPORTANT',
    affectsCalculation: false,
    remark: '项目编号扩展字段',
    status: 'FAILED',
    errorMessage: 'column "ext_project_no" of relation "quotation" already exists',
    migrationContent: `-- Flyway migration V57__add_quotation_ext_project_no.sql
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS ext_project_no VARCHAR(128) NOT NULL DEFAULT '';
COMMENT ON COLUMN quotation.ext_project_no IS 'ext_project_no — importance=IMPORTANT affects_calculation=false';`,
    flywayVersionHint: 'V57',
    createdByName: '管理员',
    createdAt: new Date(now.getTime() - 86400000).toISOString(),
  },
];

// ---- Service ----
export const ddlExtensionService = {
  extensibleTables: async (): Promise<{ data: ExtensibleTableDTO[] }> => {
    if (USE_MOCK) return Promise.resolve({ data: [...MOCK_TABLES] });
    const res = await sysApi.get('/ddl/extensible-tables') as any;
    // 后端返回 string[]（物理表名），转换为 ExtensibleTableDTO 格式
    const tableNames: string[] = Array.isArray(res.data) ? res.data : [];
    const mapped: ExtensibleTableDTO[] = tableNames.map((name) => ({
      tableName: name,
      displayName: name,
      columnCount: 0,
      columns: [],
    }));
    return { data: mapped };
  },

  columns: (tableName: string): Promise<{ data: string[] }> => {
    if (USE_MOCK) {
      return Promise.resolve({ data: MOCK_COLUMNS[tableName] || ['id', 'created_at', 'updated_at'] });
    }
    return sysApi.get(`/ddl/columns/${tableName}`) as Promise<{ data: string[] }>;
  },

  extend: (req: ExtendColumnRequest): Promise<{ data: DdlOperationDTO }> => {
    if (USE_MOCK) {
      const newRecord: DdlOperationDTO = {
        id: `ddl-${Date.now()}`,
        tableName: req.tableName,
        columnName: req.columnName,
        dataType: req.dataType,
        defaultValue: req.defaultValue,
        importance: req.importance,
        affectsCalculation: req.affectsCalculation,
        remark: req.remark,
        status: 'SUCCESS',
        migrationContent: generateMigrationSql(req),
        flywayVersionHint: `V${60 + MOCK_HISTORY.length}`,
        createdByName: '管理员',
        createdAt: new Date().toISOString(),
      };
      MOCK_HISTORY = [newRecord, ...MOCK_HISTORY];
      return Promise.resolve({ data: newRecord });
    }
    return sysApi.post('/ddl/extend-column', req) as Promise<{ data: DdlOperationDTO }>;
  },

  history: (params: {
    page?: number;
    size?: number;
    status?: string;
  }): Promise<{ data: DdlHistoryPage }> => {
    if (USE_MOCK) {
      const page = params.page ?? 0;
      const size = params.size ?? 10;
      let list = [...MOCK_HISTORY];
      if (params.status) {
        list = list.filter((r) => r.status === params.status);
      }
      const total = list.length;
      const content = list.slice(page * size, page * size + size);
      return Promise.resolve({
        data: {
          content,
          totalElements: total,
          totalPages: Math.ceil(total / size),
          number: page,
          size,
        },
      });
    }
    return sysApi.get('/ddl/history', { params }) as Promise<{ data: DdlHistoryPage }>;
  },
};

// 前端预生成 Migration SQL（与后端最终生成保持一致结构）
export function generateMigrationSql(req: ExtendColumnRequest): string {
  const vHint = `V??`;
  const fileName = `${vHint}__add_${req.tableName}_${req.columnName}.sql`;
  const colDef = buildColumnDef(req.dataType, req.defaultValue);
  return `-- Flyway migration ${fileName}
ALTER TABLE ${req.tableName} ADD COLUMN IF NOT EXISTS ${req.columnName} ${colDef};
COMMENT ON COLUMN ${req.tableName}.${req.columnName} IS '${req.columnName} — importance=${req.importance} affects_calculation=${req.affectsCalculation}';`;
}

function buildColumnDef(dataType: string, defaultValue: string): string {
  const defStr = defaultValue !== '' ? ` NOT NULL DEFAULT '${defaultValue}'` : ' NULL';
  return `${dataType}${defStr}`;
}
