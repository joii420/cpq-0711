// V6 staging-based 导入向导 — Session 服务
// 对应后端 /api/cpq/import-session/* 端点
// 设计文档：docs/superpowers/specs/2026-05-12-import-v6-staging-design.md

import api from './api';
import type {
  UploadResult,
  DecisionUpdateRequest,
  CommitRequest,
  CommitResult,
} from '../types/import-v6';

export const importSessionService = {
  /**
   * Step 1：上传 Excel 文件，触发 staging 写入 + 差异检测。
   * 返回 sessionId 和 diffPayload（版本决策/冲突/孤儿行列表）。
   */
  upload: async (customerId: string, file: File): Promise<UploadResult> => {
    const fd = new FormData();
    fd.append('customerId', customerId);
    fd.append('file', file);
    const res = await api.post('/import-session/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as any;
    return res.data ?? res;
  },

  /**
   * Step 2：保存用户的版本/冲突/孤儿行决策。
   * 幂等，前端 debounce 500ms 调用。
   */
  updateDecisions: async (
    sessionId: string,
    req: DecisionUpdateRequest
  ): Promise<void> => {
    await api.put(`/import-session/${sessionId}/decisions`, req);
  },

  /**
   * Step 3：提交 — staging → mat_* + 创建报价单（一次原子事务）。
   * 成功后返回 quotationId。
   */
  commit: async (
    sessionId: string,
    req: CommitRequest
  ): Promise<CommitResult> => {
    const res = await api.post(`/import-session/${sessionId}/commit`, req) as any;
    return res.data ?? res;
  },

  /**
   * 取消：DELETE session，CASCADE 清除 staging 数据，正式表无副作用。
   */
  cancel: async (sessionId: string): Promise<void> => {
    await api.delete(`/import-session/${sessionId}`);
  },
};
