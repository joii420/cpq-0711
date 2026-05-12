import api from './api';
import type {
  ImportResultDTOV5,
  ResolutionDTO,
  BasicDataDiffDTO,
  CustomerDataConflictDTO,
  OrphanRowDTO,
} from '../types/import-v5';

// Mock 开关：VITE_USE_MOCK_IMPORT_V5=true 时返回硬编码假数据
const USE_MOCK = import.meta.env.VITE_USE_MOCK_IMPORT_V5 === 'true';

// ────────────────────────────────────────────────
// Mock 数据（含 2 条 basic diff + 1 条 customer conflict）
// ────────────────────────────────────────────────
const MOCK_BASIC_DIFFS: BasicDataDiffDTO[] = [
  {
    tableName: 'mat_part',
    rowKey: 'HF-2024-001',
    fieldName: 'unit_price',
    fieldLabel: '单价',
    oldValue: 128.5,
    newValue: 135.0,
    importance: 'CRITICAL',
    affectsCalculation: true,
  },
  {
    tableName: 'mat_part',
    rowKey: 'HF-2024-001',
    fieldName: 'lead_time_days',
    fieldLabel: '交期(天)',
    oldValue: 30,
    newValue: 25,
    importance: 'IMPORTANT',
    affectsCalculation: false,
  },
  {
    tableName: 'process_cost',
    rowKey: 'PROC-001',
    fieldName: 'cost_per_unit',
    fieldLabel: '工序单价',
    oldValue: 45.0,
    newValue: 48.0,
    importance: 'NORMAL',
    affectsCalculation: true,
  },
];

const MOCK_CUSTOMER_CONFLICTS: CustomerDataConflictDTO[] = [
  {
    customerId: 'CUST-001',
    hfPartNo: 'HF-2024-001',
    tableName: 'customer_pricing',
    rowKey: 'CP-001',
    fields: [
      {
        fieldName: 'discount_rate',
        fieldLabel: '折扣率',
        existingValue: '0.85',
        importValue: '0.80',
        importance: 'CRITICAL',
        affectsCalculation: true,
      },
      {
        fieldName: 'min_order_qty',
        fieldLabel: '最小起订量',
        existingValue: 100,
        importValue: 200,
        importance: 'IMPORTANT',
        affectsCalculation: false,
      },
    ],
  },
];

const MOCK_ORPHAN_ROWS: OrphanRowDTO[] = [
  {
    tableName: 'mat_fee',
    rowKey: '1:HF-2024-001:INCOMING_ANNUAL_DOWN:1::::: ',
    partNo: 'HF-2024-001',
    displayLabel: 'INCOMING_ANNUAL_DOWN 项次=1 (Excel 中无此行)',
    rowSnapshot: { fee_type: 'INCOMING_ANNUAL_DOWN', seq_no: 1, fee_value: 500.0 },
    importance: 'NORMAL',
  },
  {
    tableName: 'mat_fee',
    rowKey: '1:HF-2024-001:ASSEMBLY_ANNUAL_DOWN:1::::: ',
    partNo: 'HF-2024-001',
    displayLabel: 'ASSEMBLY_ANNUAL_DOWN 项次=1 (Excel 中无此行)',
    rowSnapshot: { fee_type: 'ASSEMBLY_ANNUAL_DOWN', seq_no: 1, fee_value: 300.0 },
    importance: 'NORMAL',
  },
];

const MOCK_PREVIEW_RESULT: ImportResultDTOV5 = {
  status: 'PREVIEW_OK',
  importRecordId: null,
  totalRows: 42,
  validation: { hasErrors: false, errors: [], warnings: ['第 3 行备注字段超长，已截断'] },
  basicDataDiffs: MOCK_BASIC_DIFFS,
  customerDataConflicts: MOCK_CUSTOMER_CONFLICTS,
  orphanRows: MOCK_ORPHAN_ROWS,
};

const MOCK_CONFIRM_RESULT: ImportResultDTOV5 = {
  status: 'IMPORT_SUCCESS',
  importRecordId: 'IR-MOCK-001',
  totalRows: 42,
  validation: { hasErrors: false, errors: [], warnings: [] },
  basicDataDiffs: [],
  customerDataConflicts: [],
  orphanRows: [],
};

// ────────────────────────────────────────────────
// 服务实现
// ────────────────────────────────────────────────
export const basicDataImportV5Service = {
  preview: async (file: File, customerId: string, templateKind: string = 'QUOTATION'): Promise<ImportResultDTOV5> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 800));
      return MOCK_PREVIEW_RESULT;
    }
    const fd = new FormData();
    fd.append('file', file);
    fd.append('customerId', customerId);
    fd.append('templateKind', templateKind);  // V94: QUOTATION/COSTING 决定 sheet 配置选择
    const res = await api.post('/import/basic-data/v5/preview', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as any;
    return res.data ?? res;
  },

  confirm: async (
    file: File,
    customerId: string,
    resolutions: ResolutionDTO[],
    templateKind: string = 'QUOTATION',
    partVersionDecisions?: Record<string, string>
  ): Promise<ImportResultDTOV5> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 1000));
      return MOCK_CONFIRM_RESULT;
    }
    const fd = new FormData();
    fd.append('file', file);
    fd.append('customerId', customerId);
    fd.append('resolutions', JSON.stringify(resolutions));
    fd.append('templateKind', templateKind);
    if (partVersionDecisions && Object.keys(partVersionDecisions).length > 0) {
      fd.append('partVersionDecisions', JSON.stringify(partVersionDecisions));
    }
    const res = await api.post('/import/basic-data/v5/confirm', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as any;
    return res.data ?? res;
  },
};
