import api from './api';
import type { DriftDetectionResult } from '../types/quotation-drift';

// Mock 开关
const USE_MOCK = import.meta.env.VITE_USE_MOCK_DRIFT === 'true';

// ---- Mock 数据 ----

const MOCK_DRIFT_RESULT: DriftDetectionResult = {
  hasDrift: true,
  driftedRecords: [
    {
      tableName: 'mat_fee',
      recordId: 'rec-002',
      hfPartNo: 'HF-A001',
      referencedVersion: 2,
      currentVersion: 3,
      displayMessage: 'mat_fee 升至 v3，原 v2 已过期',
    },
  ],
  checkedAt: new Date().toISOString(),
};

// ---- 服务实现 ----

export const quotationDriftService = {
  /**
   * 刷新报价单引用的版本（SALES_REP 专用）
   * POST /api/cpq/quotations/{id}/refresh-versions
   */
  refreshVersions: async (quotationId: string): Promise<void> => {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 800));
      // mock 成功
      return;
    }
    await api.post(`/quotations/${quotationId}/refresh-versions`);
  },

  /**
   * 获取 mock 漂移检测结果（供 QuotationStep2 使用，实际从 getById 返回的 driftDetection 字段读取）
   */
  getMockDriftResult: (): DriftDetectionResult => MOCK_DRIFT_RESULT,
};
