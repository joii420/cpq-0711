import api from './api';
import type { ChangeLogEntryDTO, ChangeLogPageDTO } from '../types/versioning';

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_CHANGELOG === 'true';

// ---- Mock 数据 ----

const MOCK_ENTRIES: ChangeLogEntryDTO[] = [
  {
    id: 'log-001',
    tableName: 'mat_fee',
    recordId: 'rec-001',
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    fieldName: 'material_cost',
    fieldLabel: '材料费用',
    oldValue: 120.5,
    newValue: 135.0,
    importance: 'CRITICAL',
    affectsCalculation: true,
    changeSource: 'V5_IMPORT',
    note: '2026Q2 原材料涨价调整',
    changedAt: '2026-04-25T10:30:00Z',
    changedBy: 'user-001',
    changedByName: '张三',
    importRecordId: 'import-001',
  },
  {
    id: 'log-002',
    tableName: 'mat_fee',
    recordId: 'rec-001',
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    fieldName: 'surface_fee',
    fieldLabel: '表面处理费',
    oldValue: 12.0,
    newValue: 15.5,
    importance: 'IMPORTANT',
    affectsCalculation: true,
    changeSource: 'V5_IMPORT',
    note: '镀层工艺升级导致费用增加',
    changedAt: '2026-04-25T10:30:00Z',
    changedBy: 'user-001',
    changedByName: '张三',
    importRecordId: 'import-001',
  },
  {
    id: 'log-003',
    tableName: 'mat_process',
    recordId: 'rec-004',
    customerId: 'cust-001',
    hfPartNo: 'HF-B002',
    fieldName: 'process_time',
    fieldLabel: '加工工时',
    oldValue: 2.5,
    newValue: 2.8,
    importance: 'NORMAL',
    affectsCalculation: true,
    changeSource: 'MANUAL_EDIT',
    changedAt: '2026-04-22T14:00:00Z',
    changedBy: 'user-002',
    changedByName: '李四',
  },
  {
    id: 'log-004',
    tableName: 'plating_fee',
    recordId: 'rec-006',
    customerId: 'cust-002',
    hfPartNo: 'HF-C003',
    fieldName: 'plating_cost',
    fieldLabel: '电镀费用',
    oldValue: null,
    newValue: 28.0,
    importance: 'CRITICAL',
    affectsCalculation: true,
    changeSource: 'SYSTEM_INIT',
    note: '初始化电镀费用数据',
    changedAt: '2026-04-18T16:00:00Z',
    changedBy: 'user-003',
    changedByName: '王五',
  },
  {
    id: 'log-005',
    tableName: 'mat_fee',
    recordId: 'rec-002',
    customerId: 'cust-001',
    hfPartNo: 'HF-A001',
    fieldName: 'transport_fee',
    fieldLabel: '运费',
    oldValue: 8.0,
    newValue: 9.5,
    importance: 'NORMAL',
    affectsCalculation: false,
    changeSource: 'V5_IMPORT',
    changedAt: '2026-04-20T09:00:00Z',
    changedBy: 'user-002',
    changedByName: '李四',
    importRecordId: 'import-002',
  },
];

// ---- 参数类型 ----

export interface ChangeLogSearchParams {
  customerId?: string;
  hfPartNo?: string;
  tableName?: string;
  fieldName?: string;
  importanceList?: string[];
  changeSourceList?: string[];
  startTime?: string;
  endTime?: string;
  page?: number;
  size?: number;
}

// ---- 服务实现 ----

export const changeLogService = {
  /** 搜索变更日志 */
  search: async (params: ChangeLogSearchParams): Promise<ChangeLogPageDTO> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400));
      let items = [...MOCK_ENTRIES];
      if (params.customerId) items = items.filter((i) => i.customerId === params.customerId);
      if (params.hfPartNo) items = items.filter((i) => i.hfPartNo.includes(params.hfPartNo!));
      if (params.tableName) items = items.filter((i) => i.tableName === params.tableName);
      if (params.fieldName) items = items.filter((i) => i.fieldName.includes(params.fieldName!));
      if (params.importanceList && params.importanceList.length > 0) {
        items = items.filter((i) => params.importanceList!.includes(i.importance));
      }
      if (params.changeSourceList && params.changeSourceList.length > 0) {
        items = items.filter((i) => params.changeSourceList!.includes(i.changeSource));
      }
      if (params.startTime) {
        items = items.filter((i) => new Date(i.changedAt) >= new Date(params.startTime!));
      }
      if (params.endTime) {
        items = items.filter((i) => new Date(i.changedAt) <= new Date(params.endTime!));
      }
      items = items.sort((a, b) => new Date(b.changedAt).getTime() - new Date(a.changedAt).getTime());
      const page = params.page ?? 0;
      const size = params.size ?? 20;
      return {
        items: items.slice(page * size, (page + 1) * size),
        page,
        size,
        total: items.length,
      };
    }
    const res = await api.get('/change-log/search', { params }) as any;
    // 后端返回 Spring Page 格式（content / totalElements），转换为前端期望的 items / total
    const pageData = res.data ?? res;
    return {
      items: pageData.content ?? pageData.items ?? [],
      total: pageData.totalElements ?? pageData.total ?? 0,
      page: pageData.number ?? pageData.page ?? params.page ?? 0,
      size: pageData.size ?? params.size ?? 20,
    };
  },

  /** 导出变更日志（触发文件下载） */
  export: (params: ChangeLogSearchParams & { format: 'csv' | 'xlsx' }) => {
    if (USE_MOCK) {
      // mock 模式下生成一个假下载
      const csvContent = 'id,tableName,fieldName,oldValue,newValue,changedAt\n' +
        MOCK_ENTRIES.map((e) =>
          `${e.id},${e.tableName},${e.fieldName},${e.oldValue},${e.newValue},${e.changedAt}`
        ).join('\n');
      const blob = new Blob([csvContent], { type: 'text/csv' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `change-log-${Date.now()}.${params.format}`;
      a.click();
      URL.revokeObjectURL(url);
      return Promise.resolve();
    }
    // 真实场景：构建下载 URL 并触发
    const queryParams = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        if (Array.isArray(v)) {
          v.forEach((item) => queryParams.append(k, item));
        } else {
          queryParams.set(k, String(v));
        }
      }
    });
    const url = `/api/cpq/change-log/export?${queryParams.toString()}`;
    window.open(url, '_blank');
    return Promise.resolve();
  },
};
