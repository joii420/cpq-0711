// ─────────────────────────────────────────────────────────────────────────────
// quoteDatasetImportService —— 报价数据「导入即建单」链路（task-260907 · F-3 / F-4 / F-7）
//
// 三个端点逐字对齐 `dev-docs/task-260907-报价导入建单切ds新表/api.md` §1 / §2 / §3：
//   POST /dataset/quote/quotation-import          异步收单，立即返回 importRecordId
//   GET  /dataset/quote/quotation-import/{id}     轮询进度 / 结果 / 错误
//   POST /dataset/quote/create-quotation          由已成功的导入批次建单建行
//
// 🚫 与 `POST /dataset/{dataset}/import`（DatasetImportDrawer 共用，基础资料维护也在用）
//    **完全无关**，一个字节都不碰（api.md §0 / §5，AC-15）。
// 🚫 与 `basicDataImportV6Service`（旧「从基础数据导入」）也无关。
//    🪦 该 service 与其抽屉已于 2026-09-07 随 task-260907 F-1 删除（AC-13）；
//       此处保留这句**边界声明**是为了拦住「这两条线是不是一回事」的误认 —— 它们从来不是。
// ─────────────────────────────────────────────────────────────────────────────
import api from './api';

/** 逐 sheet 结果（api.md §2 `summary[]`）。`kind` 决定看哪一组计数。 */
export interface QuoteImportSheetSummary {
  sheetName: string;
  /** PLAIN = 免版本（PlainTableWriter）；VERSIONED = 带版本（VersionedGroupWriter） */
  kind: 'PLAIN' | 'VERSIONED';
  /** kind=PLAIN */
  inserted?: number;
  updated?: number;
  /** kind=VERSIONED */
  axisCount?: number;
  created?: number;
  upgraded?: number;
  unchanged?: number;
}

/** 校验错误（api.md §2 `errors[]`）。整份拒收时**全部**下发，不是第一条。 */
export interface QuoteImportValidationError {
  sheetName: string;
  rowNum: number;
  columnLabel: string;
  value?: string | number | null;
  reason: string;
}

export interface QuoteImportProgress {
  done: number;
  total: number;
  current: string;
}

/** 轮询响应（api.md §2）。 */
export interface QuoteImportRecord {
  importRecordId: string;
  systemType: string;
  status: 'PROCESSING' | 'SUCCESS' | 'FAILED';
  originalFileName?: string;
  totalRows?: number;
  successRows?: number;
  failedRows?: number;
  createdAt?: string;
  progress?: QuoteImportProgress | null;
  summary?: QuoteImportSheetSummary[];
  errors?: QuoteImportValidationError[];
}

/** POST /dataset/quote/quotation-import 的即时响应（api.md §1）。 */
export interface QuoteImportAccepted {
  importRecordId: string;
  systemType: string;
  status: string;
}

/** POST /dataset/quote/create-quotation 的响应（api.md §3）。 */
export interface QuoteCreateQuotationResult {
  quotationId: string;
  importRecordId: string;
  lineItemsCount: number;
  /** 恒 true。🚫 不许靠 cardValuesReady==false 猜要不要轮询（api.md §3）。 */
  materializing: boolean;
}

export interface CreateQuotationPayload {
  importRecordId: string;
  customerId: string;
  name: string;
  categoryId?: string;
  customerTemplateId: string;
  costingTemplateId?: string;
}

const BASE = '/dataset/quote';

/** 轮询超时错误的判别标记（与 quotationService.pollMaterializeStatus 同款约定）。 */
export interface PollTimeoutError extends Error {
  isPollTimeout?: boolean;
}

export const quoteDatasetImportService = {
  /**
   * F-3：上传（customerId 必选）。POST 立即返回 `{importRecordId, status:'PROCESSING'}`，
   * 解析与写库在后台线程；调用方用 `pollImportRecord` 轮询。
   */
  async importQuoteDataset(customerId: string, file: File): Promise<QuoteImportAccepted> {
    const fd = new FormData();
    fd.append('customerId', customerId);
    fd.append('file', file);
    const res: any = await api.post(`${BASE}/quotation-import`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return (res?.data ?? res) as QuoteImportAccepted;
  },

  /** 单次查询导入记录（api.md §2）。 */
  async getImportRecord(recordId: string): Promise<QuoteImportRecord> {
    const res: any = await api.get(`${BASE}/quotation-import/${recordId}`);
    return (res?.data ?? res) as QuoteImportRecord;
  },

  /**
   * F-4：轮询到终态（SUCCESS / FAILED）。
   *
   * ⚠️ **必须有上限**（fronttask F-4 ④）：超时抛 `isPollTimeout=true` 的错误，调用方给可重试入口，
   *    🚫 不许无限轮询转圈。默认 20 分钟，与 V6 侧 `basicDataImportV6Service.pollImportResult`
   *    的兜底口径一致（大文件 1845 行实测 4~10s，20 分钟是「后台真的死了」的判据）。
   */
  async pollImportRecord(
    recordId: string,
    opts?: {
      intervalMs?: number;
      timeoutMs?: number;
      onTick?: (rec: QuoteImportRecord) => void;
      /** 返回 true 时立即停止轮询（调用方已放弃本次等待）。 */
      shouldStop?: () => boolean;
    },
  ): Promise<QuoteImportRecord | null> {
    const intervalMs = opts?.intervalMs ?? 1500;
    const timeoutMs = opts?.timeoutMs ?? 20 * 60 * 1000;
    const start = Date.now();
    while (true) {
      if (opts?.shouldStop?.()) return null;
      const rec = await this.getImportRecord(recordId);
      opts?.onTick?.(rec);
      if (rec?.status && rec.status !== 'PROCESSING') return rec;
      if (Date.now() - start > timeoutMs) {
        const err = new Error(
          '导入等待超时：后台可能仍在处理，可点「继续等待」重试，或到【导入历史】查看结果',
        ) as PollTimeoutError;
        err.isPollTimeout = true;
        throw err;
      }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
  },

  /**
   * F-7：由已成功的导入批次建单建行（同步段），物化转后台。
   * 响应 `materializing=true` 是**唯一显式的「要去轮询」信号**（api.md §3）。
   */
  async createQuotation(payload: CreateQuotationPayload): Promise<QuoteCreateQuotationResult> {
    const res: any = await api.post(`${BASE}/create-quotation`, payload);
    return (res?.data ?? res) as QuoteCreateQuotationResult;
  },
};

export default quoteDatasetImportService;
