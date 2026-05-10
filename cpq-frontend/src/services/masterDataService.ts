import api from './api';

// ---- TypeScript 类型定义 ----

export interface TableSummaryDTO {
  tableName: string;
  displayName: string;
  group: 'GLOBAL' | 'CUSTOMER' | 'ELEMENT';
  rowCount: number;
  lastUpdatedAt: string | null; // ISO 8601
  v1Disabled: boolean;
  primaryKeyField: string;
  customerScoped: boolean;
}

export interface MasterDataOverviewDTO {
  customerId: string | null;
  customerName: string | null; // null = 全部客户
  tables: TableSummaryDTO[];
  generatedAt: string;
}

export interface ColumnMetadataDTO {
  /** 物理列名(snake_case),与后端 ColumnMetadataDTO.columnName 对齐 */
  columnName: string;
  /** 显示名,来自 basic_data_attribute.variable_label;无则回退 columnName */
  label: string;
  /** 来自 basic_data_attribute.data_type:IDENTIFIER / VALUE;空时由前端按运行时类型推断渲染 */
  dataType: string | null;
  importanceLevel: 'CRITICAL' | 'IMPORTANT' | 'NORMAL';
}

export interface PagedTableDataDTO {
  tableName: string;
  columns: ColumnMetadataDTO[];
  rows: Record<string, any>[];
  page: number;
  size: number;
  total: number;
  v1Disabled: boolean;
}

// ---- Mock 数据（后端未就绪时使用） ----

const USE_MOCK = import.meta.env.VITE_USE_MOCK_MASTER_DATA === 'true';

const MOCK_OVERVIEW: MasterDataOverviewDTO = {
  customerId: null,
  customerName: null,
  generatedAt: new Date().toISOString(),
  tables: [
    // GLOBAL 组
    {
      tableName: 'mat_part',
      displayName: '料号主档',
      group: 'GLOBAL',
      rowCount: 1842,
      lastUpdatedAt: '2026-04-25T10:30:00Z',
      v1Disabled: false,
      primaryKeyField: 'part_no',
      customerScoped: false,
    },
    {
      tableName: 'mat_bom',
      displayName: 'BOM 清单',
      group: 'GLOBAL',
      rowCount: 5216,
      lastUpdatedAt: '2026-04-25T10:30:00Z',
      v1Disabled: false,
      primaryKeyField: 'bom_id',
      customerScoped: false,
    },
    {
      tableName: 'exchange_rate',
      displayName: '汇率表',
      group: 'GLOBAL',
      rowCount: 48,
      lastUpdatedAt: '2026-04-20T08:00:00Z',
      v1Disabled: false,
      primaryKeyField: 'rate_id',
      customerScoped: false,
    },
    {
      tableName: 'customer_tax',
      displayName: '客户税率',
      group: 'GLOBAL',
      rowCount: 120,
      lastUpdatedAt: '2026-04-18T14:00:00Z',
      v1Disabled: false,
      primaryKeyField: 'tax_id',
      customerScoped: false,
    },
    // CUSTOMER 组
    {
      tableName: 'customer_price',
      displayName: '客户价格',
      group: 'CUSTOMER',
      rowCount: 3400,
      lastUpdatedAt: '2026-04-26T09:00:00Z',
      v1Disabled: false,
      primaryKeyField: 'price_id',
      customerScoped: true,
    },
    {
      tableName: 'customer_discount',
      displayName: '客户折扣',
      group: 'CUSTOMER',
      rowCount: 210,
      lastUpdatedAt: '2026-04-22T11:20:00Z',
      v1Disabled: false,
      primaryKeyField: 'discount_id',
      customerScoped: true,
    },
    {
      tableName: 'customer_material_mapping',
      displayName: '客户料号映射',
      group: 'CUSTOMER',
      rowCount: 986,
      lastUpdatedAt: '2026-04-24T16:45:00Z',
      v1Disabled: false,
      primaryKeyField: 'mapping_id',
      customerScoped: true,
    },
    // ELEMENT 组
    {
      tableName: 'element_process',
      displayName: '工序元素',
      group: 'ELEMENT',
      rowCount: 0,
      lastUpdatedAt: null,
      v1Disabled: true,
      primaryKeyField: 'process_id',
      customerScoped: false,
    },
    {
      tableName: 'element_material',
      displayName: '物料元素',
      group: 'ELEMENT',
      rowCount: 0,
      lastUpdatedAt: null,
      v1Disabled: true,
      primaryKeyField: 'material_id',
      customerScoped: false,
    },
  ],
};

const MOCK_TABLE_DATA: Record<string, PagedTableDataDTO> = {
  mat_part: {
    tableName: 'mat_part',
    columns: [
      { columnName: 'part_no', label: '料号', dataType: 'IDENTIFIER', importanceLevel: 'CRITICAL' },
      { columnName: 'part_name', label: '料号名称', dataType: 'VALUE', importanceLevel: 'CRITICAL' },
      { columnName: 'unit_weight', label: '单重(g)', dataType: 'VALUE', importanceLevel: 'CRITICAL' },
      { columnName: 'material_type', label: '材质', dataType: 'VALUE', importanceLevel: 'IMPORTANT' },
      { columnName: 'supplier_code', label: '供应商编码', dataType: 'VALUE', importanceLevel: 'IMPORTANT' },
      { columnName: 'created_at', label: '创建时间', dataType: 'VALUE', importanceLevel: 'NORMAL' },
      { columnName: 'remark', label: '备注', dataType: 'VALUE', importanceLevel: 'NORMAL' },
    ],
    rows: Array.from({ length: 20 }, (_, i) => ({
      part_no: `PN-${String(1001 + i).padStart(5, '0')}`,
      part_name: `零件名称-${i + 1}`,
      unit_weight: (Math.random() * 100).toFixed(2),
      material_type: ['钢', '铝', '铜', '塑料'][i % 4],
      supplier_code: `SUP-${String(i + 1).padStart(3, '0')}`,
      created_at: '2026-01-15T08:00:00Z',
      remark: i % 3 === 0 ? '重点物料' : null,
    })),
    page: 0,
    size: 50,
    total: 1842,
    v1Disabled: false,
  },
};

function mockDelay<T>(data: T, ms = 400): Promise<{ code: number; data: T }> {
  return new Promise((resolve) => setTimeout(() => resolve({ code: 200, data }), ms));
}

// ---- Service ----

export const masterDataService = {
  getOverview: async (customerId?: string | null): Promise<{ code: number; data: MasterDataOverviewDTO }> => {
    if (USE_MOCK) {
      const result = { ...MOCK_OVERVIEW, customerId: customerId || null };
      return mockDelay(result);
    }
    return api.get('/master-data/overview', {
      params: customerId ? { customerId } : undefined,
    });
  },

  queryTable: async (
    tableName: string,
    params: {
      customerId?: string | null;
      page: number;
      size: number;
      search?: string;
    },
  ): Promise<{ code: number; data: PagedTableDataDTO }> => {
    if (USE_MOCK) {
      const mock = MOCK_TABLE_DATA[tableName];
      if (mock) {
        return mockDelay(mock);
      }
      // 其他表返回空数据
      return mockDelay({
        tableName,
        columns: [
          { columnName: 'id', label: 'ID', dataType: 'IDENTIFIER', importanceLevel: 'CRITICAL' as const },
          { columnName: 'name', label: '名称', dataType: 'VALUE', importanceLevel: 'CRITICAL' as const },
        ],
        rows: [],
        page: 0,
        size: 50,
        total: 0,
        v1Disabled: false,
      });
    }
    const cleanParams: Record<string, any> = { page: params.page, size: params.size };
    if (params.customerId) cleanParams.customerId = params.customerId;
    if (params.search) cleanParams.search = params.search;
    return api.get(`/master-data/table/${tableName}`, { params: cleanParams });
  },

  getRowDetail: async (
    tableName: string,
    rowId: string,
  ): Promise<{ code: number; data: Record<string, any> }> => {
    if (USE_MOCK) {
      const mock = MOCK_TABLE_DATA[tableName];
      const pkField = mock?.columns[0]?.columnName ?? 'id';
      const row = mock?.rows.find((r) => String(r[pkField]) === rowId);
      return mockDelay(row || { id: rowId, name: 'Mock Row', remark: '模拟数据' });
    }
    return api.get(`/master-data/table/${tableName}/row/${rowId}`);
  },

  // 获取客户列表（用于客户选择器）
  listCustomers: async (): Promise<{ code: number; data: { id: string; name: string }[] }> => {
    if (USE_MOCK) {
      return mockDelay([
        { id: 'cust-001', name: '上海某制造公司' },
        { id: 'cust-002', name: '北京某零件厂' },
        { id: 'cust-003', name: '广州某汽车部件' },
      ]);
    }
    return api.get('/customers', { params: { page: 0, size: 200 } });
  },
};
