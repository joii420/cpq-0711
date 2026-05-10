import api from './api';
import type {
  VersionHistoryPageDTO,
  VersionHistoryItemDTO,
  VersionCompareDTO,
} from '../types/versioning';

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_VERSIONING === 'true';

// ---- Mock 数据 ----

const MOCK_HISTORY_ITEMS: VersionHistoryItemDTO[] = [
  {
    tableName: 'mat_fee',
    recordId: 'rec-001',
    version: 3,
    isCurrent: true,
    businessKey: { customer_id: 'cust-001', hf_part_no: 'HF-A001' },
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    updatedAt: '2026-04-25T10:30:00Z',
    updatedBy: 'user-001',
    updatedByName: '张三',
  },
  {
    tableName: 'mat_fee',
    recordId: 'rec-002',
    version: 2,
    isCurrent: false,
    businessKey: { customer_id: 'cust-001', hf_part_no: 'HF-A001' },
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    updatedAt: '2026-04-20T09:00:00Z',
    updatedBy: 'user-002',
    updatedByName: '李四',
  },
  {
    tableName: 'mat_fee',
    recordId: 'rec-003',
    version: 1,
    isCurrent: false,
    businessKey: { customer_id: 'cust-001', hf_part_no: 'HF-A001' },
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    updatedAt: '2026-04-10T08:00:00Z',
    updatedBy: 'user-001',
    updatedByName: '张三',
  },
  {
    tableName: 'mat_process',
    recordId: 'rec-004',
    version: 2,
    isCurrent: true,
    businessKey: { customer_id: 'cust-001', hf_part_no: 'HF-B002' },
    customerId: 'cust-001',
    hfPartNo: 'HF-B002',
    updatedAt: '2026-04-22T14:00:00Z',
    updatedBy: 'user-002',
    updatedByName: '李四',
  },
  {
    tableName: 'mat_process',
    recordId: 'rec-005',
    version: 1,
    isCurrent: false,
    businessKey: { customer_id: 'cust-001', hf_part_no: 'HF-B002' },
    customerId: 'cust-001',
    hfPartNo: 'HF-B002',
    updatedAt: '2026-04-15T11:00:00Z',
    updatedBy: 'user-001',
    updatedByName: '张三',
  },
  {
    tableName: 'plating_fee',
    recordId: 'rec-006',
    version: 1,
    isCurrent: true,
    businessKey: { customer_id: 'cust-002', hf_part_no: 'HF-C003' },
    customerId: 'cust-002',
    hfPartNo: 'HF-C003',
    updatedAt: '2026-04-18T16:00:00Z',
    updatedBy: 'user-003',
    updatedByName: '王五',
  },
];

const MOCK_COMPARE: VersionCompareDTO = {
  tableName: 'mat_fee',
  recordA: MOCK_HISTORY_ITEMS[1], // v2
  recordB: MOCK_HISTORY_ITEMS[0], // v3
  fieldDiffs: [
    { fieldName: 'material_cost', fieldLabel: '材料费用', valueA: 120.5, valueB: 135.0, sameValue: false },
    { fieldName: 'process_fee', fieldLabel: '加工费', valueA: 45.0, valueB: 45.0, sameValue: true },
    { fieldName: 'surface_fee', fieldLabel: '表面处理费', valueA: 12.0, valueB: 15.5, sameValue: false },
    { fieldName: 'packaging_fee', fieldLabel: '包装费', valueA: 3.0, valueB: 3.0, sameValue: true },
    { fieldName: 'transport_fee', fieldLabel: '运费', valueA: 8.0, valueB: 9.5, sameValue: false },
    { fieldName: 'remark', fieldLabel: '备注', valueA: '原始报价', valueB: '更新报价 2026Q2', sameValue: false },
  ],
};

// ---- 参数类型 ----

export interface VersionHistoryParams {
  tableName?: string;
  customerId?: string;
  hfPartNo?: string;
  page?: number;
  size?: number;
}

// ---- 服务实现 ----

export const versioningService = {
  /** 查询历史版本列表 */
  listHistory: async (params: VersionHistoryParams): Promise<VersionHistoryPageDTO> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400));
      let items = [...MOCK_HISTORY_ITEMS];
      if (params.tableName) items = items.filter((i) => i.tableName === params.tableName);
      if (params.customerId) items = items.filter((i) => i.customerId === params.customerId);
      if (params.hfPartNo) items = items.filter((i) => i.hfPartNo.includes(params.hfPartNo!));
      items = items.sort((a, b) => b.version - a.version);
      const page = params.page ?? 0;
      const size = params.size ?? 20;
      return {
        items: items.slice(page * size, (page + 1) * size),
        page,
        size,
        total: items.length,
      };
    }
    const res = await api.get('/versioning/history', { params }) as any;
    return res.data;
  },

  /** 获取单条历史版本详情 */
  getRowDetail: async (recordId: string): Promise<VersionHistoryItemDTO> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 200));
      const found = MOCK_HISTORY_ITEMS.find((i) => i.recordId === recordId);
      if (!found) throw new Error('记录不存在');
      return found;
    }
    const res = await api.get(`/versioning/history/${recordId}`) as any;
    return res.data;
  },

  /** 对比两个版本 */
  compareVersions: async (params: {
    tableName: string;
    recordIdA: string;
    recordIdB: string;
  }): Promise<VersionCompareDTO> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 500));
      // 找到对应记录
      const recA = MOCK_HISTORY_ITEMS.find((i) => i.recordId === params.recordIdA);
      const recB = MOCK_HISTORY_ITEMS.find((i) => i.recordId === params.recordIdB);
      return {
        ...MOCK_COMPARE,
        recordA: recA ?? MOCK_COMPARE.recordA,
        recordB: recB ?? MOCK_COMPARE.recordB,
      };
    }
    const res = await api.get('/versioning/compare', { params }) as any;
    return res.data;
  },
};
