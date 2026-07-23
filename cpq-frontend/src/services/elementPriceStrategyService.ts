import api from './api';
import type {
  PriceSourceDTO,
  PriceSourceUpsertRequest,
  SourceStatus,
  PriceImportResultDTO,
  ElementPriceRowDTO,
  PriceMatrixDTO,
  PageResult,
  StrategyBundleDTO,
  StrategyDTO,
  StrategyUpsertRequest,
  SimulateRequest,
  SimulateRowDTO,
  StrategyHistoryDTO,
} from '../types/element-price-strategy';

/**
 * 元素单价维护与价格策略（task-0722）— 服务层
 * 权威依据：dev-docs/task-0722-元素价格策略/api.md
 * 前缀 /api/cpq/element-price（api baseURL 已含 /api/cpq，此处只写 /element-price/...）
 *
 * 注意：响应为裸 DTO/裸数组，不解 {code,data} 信封（api.md §0）；
 * api.ts 的响应拦截器已 `return response.data`，故这里 `await api.xxx(...)` 直接就是业务数据本体。
 */
const BASE = '/element-price';

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

/** responseType:'blob' 时拦截器已返回 Blob 本体；兜底万一被序列化成非 Blob 时重新包一层 */
function asBlob(data: unknown, mime: string): Blob {
  return data instanceof Blob ? data : new Blob([data as BlobPart], { type: mime });
}

export const elementPriceStrategyService = {
  // ── §1 价格源 ──

  async listSources(params?: { status?: SourceStatus; keyword?: string }): Promise<PriceSourceDTO[]> {
    const res = await api.get(`${BASE}/sources`, { params });
    return (res as unknown as PriceSourceDTO[]) ?? [];
  },

  async createSource(req: PriceSourceUpsertRequest): Promise<PriceSourceDTO> {
    return (await api.post(`${BASE}/sources`, req)) as unknown as PriceSourceDTO;
  },

  async updateSource(id: string, req: PriceSourceUpsertRequest): Promise<PriceSourceDTO> {
    return (await api.put(`${BASE}/sources/${encodeURIComponent(id)}`, req)) as unknown as PriceSourceDTO;
  },

  async setSourceStatus(id: string, status: SourceStatus): Promise<PriceSourceDTO> {
    return (await api.post(`${BASE}/sources/${encodeURIComponent(id)}/status`, { status })) as unknown as PriceSourceDTO;
  },

  // ── §2 价格导入 ──

  /** GET /element-price/import-template — 下载导入模板(xlsx) */
  async downloadImportTemplate(): Promise<Blob> {
    const data = await api.get(`${BASE}/import-template`, { responseType: 'blob' });
    return asBlob(data, XLSX_MIME);
  },

  /** POST /element-price/import — multipart 上传；逐行独立处理，失败行不阻断其他行入库（api.md §2.2） */
  async importPrices(file: File, sourceId: string, priceDate: string): Promise<PriceImportResultDTO> {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sourceId', sourceId);
    fd.append('priceDate', priceDate);
    const res = await api.post(`${BASE}/import`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res as unknown as PriceImportResultDTO;
  },

  // ── §3 价格表查询 ──

  async listPrices(params: {
    sourceId?: string;
    from?: string;
    to?: string;
    keyword?: string;
    page?: number;
    size?: number;
  }): Promise<PageResult<ElementPriceRowDTO>> {
    return (await api.get(`${BASE}/prices`, { params })) as unknown as PageResult<ElementPriceRowDTO>;
  },

  /** sourceId 必填；跨度 > 90 天后端返 400 */
  async matrixPrices(params: {
    sourceId: string;
    from?: string;
    to?: string;
    keyword?: string;
  }): Promise<PriceMatrixDTO> {
    return (await api.get(`${BASE}/prices/matrix`, { params })) as unknown as PriceMatrixDTO;
  },

  async exportPrices(params: { sourceId?: string; from?: string; to?: string; keyword?: string }): Promise<Blob> {
    const data = await api.get(`${BASE}/prices/export`, { params, responseType: 'blob' });
    return asBlob(data, XLSX_MIME);
  },

  async exportMatrix(params: { sourceId: string; from?: string; to?: string; keyword?: string }): Promise<Blob> {
    const data = await api.get(`${BASE}/prices/matrix/export`, { params, responseType: 'blob' });
    return asBlob(data, XLSX_MIME);
  },

  // ── §5 价格策略 ──

  /** customerNo 传 '_GLOBAL_' 读全局（核价成本口径）策略 */
  async getStrategyBundle(customerNo: string): Promise<StrategyBundleDTO> {
    return (await api.get(`${BASE}/strategies`, { params: { customerNo } })) as unknown as StrategyBundleDTO;
  },

  /** 新建或覆盖客户级默认策略 */
  async saveDefaultStrategy(req: StrategyUpsertRequest): Promise<StrategyDTO> {
    return (await api.put(`${BASE}/strategies/default`, req)) as unknown as StrategyDTO;
  },

  async createException(req: StrategyUpsertRequest): Promise<StrategyDTO> {
    return (await api.post(`${BASE}/strategies/exceptions`, req)) as unknown as StrategyDTO;
  },

  async updateException(id: string, req: StrategyUpsertRequest): Promise<StrategyDTO> {
    return (await api.put(`${BASE}/strategies/exceptions/${encodeURIComponent(id)}`, req)) as unknown as StrategyDTO;
  },

  async deleteException(id: string): Promise<void> {
    await api.delete(`${BASE}/strategies/exceptions/${encodeURIComponent(id)}`);
  },

  // ── §6 策略试算（只读、不落库） ──

  async simulate(req: SimulateRequest): Promise<SimulateRowDTO[]> {
    const res = await api.post(`${BASE}/strategies/simulate`, req);
    return (res as unknown as SimulateRowDTO[]) ?? [];
  },

  // ── §7 策略变更历史（只读） ──

  async listHistory(params: {
    customerNo: string;
    /** 传值=只看该元素例外；传 '__DEFAULT__'=只看客户级默认；不传=全部 */
    elementCode?: string;
    from?: string;
    to?: string;
    changedBy?: string;
    page?: number;
    size?: number;
  }): Promise<PageResult<StrategyHistoryDTO>> {
    return (await api.get(`${BASE}/strategies/history`, { params })) as unknown as PageResult<StrategyHistoryDTO>;
  },
};
