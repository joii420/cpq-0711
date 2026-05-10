// ============================================================
// services/quotationSnapshotService.ts
// UI-8/UI-9 快照与字段追溯服务（含 mock 开关）
// ============================================================

import api from './api';
import type { SubmissionSnapshot, FieldTraceDTO } from '../types/quotation-snapshot';

const USE_MOCK = import.meta.env.VITE_USE_MOCK_SNAPSHOT === 'true';

// ------------------------------------------------------------------
// Mock 数据
// ------------------------------------------------------------------

const MOCK_SNAPSHOT: SubmissionSnapshot = {
  snapshotAt: '2026-04-28T10:30:00',
  referencedVersions: [
    { tableName: 'mat_process', businessKey: 'HF001|CUST001', version: 'v3', displayName: 'HF001 加工费 v3' },
    { tableName: 'mat_fee', businessKey: 'HF002|CUST001', version: 'v2', displayName: 'HF002 材料费 v2' },
    { tableName: 'plating_fee', businessKey: 'HF001|CUST001', version: 'v1', displayName: 'HF001 电镀费 v1' },
  ],
  elementActualPrices: [
    { elementName: '铜', price: 68.5, currency: 'CNY' },
    { elementName: '铝', price: 22.3, currency: 'CNY' },
    { elementName: '镍', price: 145.0, currency: 'CNY' },
  ],
  formulaDefinitions: [
    {
      name: 'unit_price',
      expression: 'mat_cost + process_cost + plating_cost + profit_margin',
      description: '综合单价',
      category: '定价',
    },
    {
      name: 'mat_cost',
      expression: 'weight * element_price * (1 + scrap_rate)',
      description: '材料成本',
      category: '成本',
    },
    {
      name: 'process_cost',
      expression: 'process_hours * hourly_rate',
      description: '加工成本',
      category: '成本',
    },
  ],
  masterDataSnapshot: [
    { tableName: 'mat_process', fieldName: 'hourly_rate', value: 85, displayName: '加工工时费' },
    { tableName: 'mat_fee', fieldName: 'scrap_rate', value: 0.05, displayName: '废品率' },
    { tableName: 'plating_fee', fieldName: 'plating_rate', value: 12.5, displayName: '电镀费率' },
    { tableName: 'element_price', fieldName: 'copper_price', value: 68.5, displayName: '铜价' },
  ],
};

const MOCK_TRACE: Record<string, FieldTraceDTO> = {
  default: {
    fieldPath: 'lineItems[0].componentData[0].rowData.unit_price',
    fieldLabel: '综合单价',
    currentValue: 125.6,
    sourceType: 'FORMULA',
    referencedVersion: 'mat_process@v3',
    formula: 'mat_cost + process_cost + plating_cost + profit_margin',
    formulaInputs: {
      mat_cost: 62.3,
      process_cost: 28.5,
      plating_cost: 12.5,
      profit_margin: 22.3,
    },
    lastModifiedBy: '张三',
    lastModifiedAt: '2026-04-28T10:30:00',
    isComplex: true,
  },
};

// ------------------------------------------------------------------
// Service
// ------------------------------------------------------------------

export const quotationSnapshotService = {
  /**
   * 提交报价单（DRAFT → SUBMITTED），后端同步写 submission_snapshot
   */
  submit: (id: string): Promise<any> => {
    if (USE_MOCK) {
      return new Promise((resolve) =>
        setTimeout(() => resolve({ data: { status: 'SUBMITTED' } }), 800)
      );
    }
    return api.post(`/quotations/${id}/submit`);
  },

  /**
   * 获取提交快照
   * GET /api/cpq/quotations/{id}/snapshot
   */
  getSnapshot: (id: string): Promise<{ data: SubmissionSnapshot }> => {
    if (USE_MOCK) {
      return new Promise((resolve) =>
        setTimeout(() => resolve({ data: MOCK_SNAPSHOT }), 500)
      );
    }
    return api.get(`/quotations/${id}/snapshot`);
  },

  /**
   * 获取字段追溯信息
   * GET /api/cpq/quotations/{id}/field-trace?fieldPath=...
   */
  getFieldTrace: (id: string, fieldPath: string): Promise<{ data: FieldTraceDTO }> => {
    if (USE_MOCK) {
      const trace: FieldTraceDTO = {
        ...MOCK_TRACE.default,
        fieldPath,
      };
      return new Promise((resolve) =>
        setTimeout(() => resolve({ data: trace }), 300)
      );
    }
    return api.get(`/quotations/${id}/field-trace`, { params: { fieldPath } });
  },
};
