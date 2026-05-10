import api from './api';

export interface EvaluateRequest {
  expression: string;
  customerId?: string;
  partNo?: string;
  bindings?: Record<string, any>;
}

export interface EvaluateResponse {
  success: boolean;
  result?: any;
  error?: string;
  errorType?: 'PARSE_ERROR' | 'EVAL_ERROR' | 'CONTEXT_MISSING';
}

// ── 批量求值接口 ──────────────────────────────────────────────────────────────

export interface BatchEvaluateTask {
  expression: string;
  customerId?: string | null;
  partNo?: string | null;
  bindings?: Record<string, unknown> | null;
  driverRow?: Record<string, unknown> | null;
}

export interface BatchEvaluateResultItem {
  key: string;
  status: 'OK' | 'ERROR';
  data?: EvaluateResponse | null;
  error?: string | null;
}

/**
 * 构造与后端 batch-evaluate 接口约定的 key。
 * 格式: `expression:customerId:partNo`，null/undefined 用 "_" 占位。
 */
export function buildEvalKey(
  expression: string,
  customerId?: string | null,
  partNo?: string | null,
): string {
  return `${expression || '_'}:${customerId || '_'}:${partNo || '_'}`;
}

/**
 * 批量公式求值，自动按 200 拆分 chunk。
 * 一次 HTTP 请求替代 N 个并发单请求，用于报价单运行时路径公式批量加载。
 */
export async function batchEvaluate(tasks: BatchEvaluateTask[]): Promise<BatchEvaluateResultItem[]> {
  if (tasks.length === 0) return [];
  const chunks: BatchEvaluateTask[][] = [];
  for (let i = 0; i < tasks.length; i += 200) {
    chunks.push(tasks.slice(i, i + 200));
  }
  const all: BatchEvaluateResultItem[] = [];
  for (const chunk of chunks) {
    const resp = await api.post('/formulas/batch-evaluate', { tasks: chunk });
    all.push(...((resp?.data?.results || []) as BatchEvaluateResultItem[]));
  }
  return all;
}

export const formulaService = {
  /**
   * 公式求值(支持 BNF 路径)。
   * 用途:
   *   - 组件管理校验路径语法(留 customerId/partNo 空)
   *   - 报价单运行时含 BNF 路径的公式实时求值（一次性试算/校验保持单调用）
   */
  evaluate: (req: EvaluateRequest) =>
    api.post('/formulas/evaluate', req) as Promise<{ data: EvaluateResponse }>,
};
