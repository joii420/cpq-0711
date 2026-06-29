import api from './api';

export interface EvaluateRequest {
  expression: string;
  customerId?: string;
  partNo?: string;
  bindings?: Record<string, any>;
  /** template 实体 ID（新字段，供后端 SqlViewRuntimeContext 定位 template_sql_view） */
  templateId?: string | null;
  /** @deprecated 旧字段，保留向后兼容；新代码请用 templateId */
  costingTemplateId?: string | null;
  quotationId?: string | null;
  quotationStatus?: string | null;
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
  /** template 实体 ID（新字段，供后端 SqlViewRuntimeContext 定位 template_sql_view） */
  templateId?: string | null;
  /** @deprecated 旧字段，保留向后兼容；新代码请用 templateId */
  costingTemplateId?: string | null;
  quotationId?: string | null;
  quotationStatus?: string | null;
}

export interface BatchEvaluateResultItem {
  key: string;
  status: 'OK' | 'ERROR';
  data?: EvaluateResponse | null;
  error?: string | null;
}

/**
 * 构造与后端 batch-evaluate 接口约定的 key。
 * 格式: `expression:customerId:partNo:templateId`，null/undefined 用 "_" 占位。
 *
 * V249/V250 起后端 r.key 升级为 4 段（含 templateId 维度，用于隔离 template_sql_view 上下文）。
 * 不传 templateId 的老调用方自动得到末段 "_"，与后端 batchTemplateId=null 时的 "_" 对齐。
 */
export function buildEvalKey(
  expression: string,
  customerId?: string | null,
  partNo?: string | null,
  templateId?: string | null,
): string {
  return `${expression || '_'}:${customerId || '_'}:${partNo || '_'}:${templateId || '_'}`;
}

/**
 * 批量公式求值 — 设计目标:**一次 HTTP 请求覆盖整个报价单的全部路径求值**。
 *
 * 历史问题:CHUNK 设置过小(原 200)导致 N=2000 个 task 拆成 10 个 HTTP,违背"一次查询"目标。
 * 后端 PathBatchEvaluator 已经按 (path, customerId) 聚合到 unique-path 数条 IN SQL,
 * 单批携带数千 task 对 DB 压力可控;前端没必要再切。
 *
 * 现策略:CHUNK = 5000(实质"一次性"),正常报价单 1 个 HTTP 搞定。
 * 后端 BATCH_MAX 同步从 200 提到 5000。
 */
const BATCH_EVALUATE_CHUNK = 5000;
export async function batchEvaluate(tasks: BatchEvaluateTask[], signal?: AbortSignal): Promise<BatchEvaluateResultItem[]> {
  if (tasks.length === 0) return [];
  if (tasks.length <= BATCH_EVALUATE_CHUNK) {
    const resp = await api.post('/formulas/batch-evaluate', { tasks }, { signal });
    return (resp?.data?.results || []) as BatchEvaluateResultItem[];
  }
  // 兜底:极端大批量分片(>5000),正常路径走不到
  const chunks: BatchEvaluateTask[][] = [];
  for (let i = 0; i < tasks.length; i += BATCH_EVALUATE_CHUNK) {
    chunks.push(tasks.slice(i, i + BATCH_EVALUATE_CHUNK));
  }
  const all: BatchEvaluateResultItem[] = [];
  for (const chunk of chunks) {
    const resp = await api.post('/formulas/batch-evaluate', { tasks: chunk }, { signal });
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
