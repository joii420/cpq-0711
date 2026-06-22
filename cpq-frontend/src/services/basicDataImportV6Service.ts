import api from './api';

export interface SheetResultDTO {
  sheetName: string;
  totalRows: number;
  successRows: number;
  failedRows: number;
  errors: Array<{ rowNo: number; column: string; message: string }>;
  writtenCounts: Record<string, number>;
}

export interface ImportResultDTO {
  importRecordId: string;
  systemType: 'QUOTE' | 'PRICING';
  status: 'SUCCESS' | 'PARTIAL' | 'FAILED';
  totalSuccessRows: number;
  totalFailedRows: number;
  sheetResults: SheetResultDTO[];
}

const BASE = '/basic-data-import/v6';

/** 处理中进度（后端每完成一步写入 metadata.progress）。 */
export interface ImportProgress {
  done: number;
  total: number;
  current: string;
}

/** 从导入记录 metadata 解析处理进度；无进度（未开始/已结束）返回 null。 */
function parseProgress(rec: any): ImportProgress | null {
  try {
    const meta = typeof rec?.metadata === 'string' ? JSON.parse(rec.metadata) : rec?.metadata;
    const p = meta?.progress;
    if (p && typeof p.total === 'number' && p.total > 0) {
      return { done: Number(p.done ?? 0), total: Number(p.total), current: String(p.current ?? '') };
    }
  } catch {
    /* metadata 此刻可能是 sheetResults 或空，忽略 */
  }
  return null;
}

/** 把后端 GET /v6/{recordId} 的导入记录映射回 ImportResultDTO（metadata 内为同构 sheetResults）。 */
function recordToResult(rec: any): ImportResultDTO {
  let sheetResults: SheetResultDTO[] = [];
  try {
    const meta = typeof rec?.metadata === 'string' ? JSON.parse(rec.metadata) : rec?.metadata;
    sheetResults = (meta?.sheetResults ?? []) as SheetResultDTO[];
  } catch {
    sheetResults = [];
  }
  return {
    importRecordId: rec?.importRecordId,
    systemType: rec?.systemType,
    status: rec?.status,
    totalSuccessRows: rec?.successRows ?? 0,
    totalFailedRows: rec?.failedRows ?? 0,
    sheetResults,
  };
}

export const basicDataImportV6Service = {
  /**
   * 报价基础数据导入（19 Sheet，异步）。POST 立即返回 {importRecordId, status:'PROCESSING'}，
   * 后台处理；调用方用 pollImportResult 轮询直到终态，避免大文件撞 HTTP/代理超时。
   */
  async importQuote(customerId: string, file: File): Promise<ImportResultDTO> {
    const fd = new FormData();
    fd.append('customerId', customerId);
    fd.append('file', file);
    const res: any = await api.post(`${BASE}/quote`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return (res.data ?? res) as ImportResultDTO;
  },

  /**
   * 轮询导入记录直到终态（SUCCESS/PARTIAL/FAILED），返回映射后的 ImportResultDTO。
   * 每次轮询是独立快速 GET（不撞单请求超时）；总等待上限 timeoutMs。
   */
  async pollImportResult(
    recordId: string,
    opts?: { intervalMs?: number; timeoutMs?: number; onTick?: (rec: any) => void },
  ): Promise<ImportResultDTO> {
    const intervalMs = opts?.intervalMs ?? 1500;
    const timeoutMs = opts?.timeoutMs ?? 20 * 60 * 1000; // 20 分钟兜底
    const start = Date.now();
    // eslint-disable-next-line no-constant-condition
    while (true) {
      const rec: any = await this.getResult(recordId);
      opts?.onTick?.(rec);
      if (rec?.status && rec.status !== 'PROCESSING') return recordToResult(rec);
      if (Date.now() - start > timeoutMs) {
        throw new Error('导入超时：后台可能仍在处理，请稍后在导入记录中查看结果');
      }
      await new Promise((r) => setTimeout(r, intervalMs));
    }
  },

  /** 核价基础数据导入（24 Sheet）。无 customerId（customer_no 从 Excel 行读取）。 */
  async importPricing(file: File): Promise<ImportResultDTO> {
    const fd = new FormData();
    fd.append('file', file);
    const res: any = await api.post(`${BASE}/pricing`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return (res.data ?? res) as ImportResultDTO;
  },

  /** 查询导入记录详情。 */
  async getResult(recordId: string): Promise<Record<string, unknown>> {
    const res: any = await api.get(`${BASE}/${recordId}`);
    return (res.data ?? res) as Record<string, unknown>;
  },

  /** 从轮询返回的记录解析处理进度（无则 null）。 */
  parseProgress,
};
