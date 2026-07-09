import api from './api';

export interface MaterialRecipeLite {
  id: string;
  code: string;
  symbol: string;
  name: string;
  specLabel?: string;
  recipeType: 'locked' | 'editable' | 'partial';
  status?: 'ACTIVE' | 'INACTIVE';
  sortOrder?: number;
  /** 创建时间 (ISO8601，task-0708 · B3 新增) */
  createdAt?: string;
  /** 修改时间 (ISO8601，task-0708 · B3 新增) */
  updatedAt?: string;
  /** 仅 list({withCount:true}) 时填充 */
  boundPartsCount?: number;
}

/** 「材质管理 → 关联料号」Tab 列表项 (对应后端 MaterialRecipePartDTO) */
export interface MaterialRecipePart {
  partNo: string;
  partName?: string | null;
  specification?: string | null;
  sizeInfo?: string | null;
  productType?: string | null;          // SIMPLE / COMPOSITE
  statusCode?: string | null;           // Y / N
  unitWeight?: number | null;
  materialRecipeId?: string | null;
  materialRecipeCode?: string | null;
  materialRecipeSymbol?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface PageResultLike<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 智能推断 - 单条料号的绑定建议 */
export interface BindingSuggestion {
  partNo: string;
  partName?: string | null;
  specification?: string | null;
  sourceHints: string[];           // mat_bom.element_name 提取的依据
  candidates: SuggestionCandidate[];
}

export interface SuggestionCandidate {
  recipeId: string;
  recipeCode: string;
  recipeSymbol: string;
  recipeName: string;
  confidence: 'EXACT_CODE' | 'EXACT_SYMBOL' | 'PREFIX_MATCH';
  matchedOn: string;
}

export interface MaterialRecipeElement {
  elementCode: string;
  elementName: string;
  defaultPct: number;
  minPct?: number;
  maxPct?: number;
  isLocked: boolean;
  sortOrder: number;
}

export interface MaterialRecipeDetail extends MaterialRecipeLite {
  elements: MaterialRecipeElement[];
}

export interface ExistingPartMaterialElement {
  elementCode: string;
  elementName: string;
  pct: number;
  minPct: number | null;
  maxPct: number | null;
  isLocked: boolean;
}

export interface ExistingPartMaterial {
  hfPartNo: string;
  recipeBound: boolean;
  recipeCode: string | null;
  recipeSymbol: string | null;
  recipeName: string | null;
  recipeSpec: string | null;
  recipeType: 'locked' | 'editable' | 'partial' | null;
  elements: ExistingPartMaterialElement[];
}

export interface MaterialRecipeUpsertRequest {
  code: string;
  symbol: string;
  /** task-0708：管理 UI 不再收集，导入/新建置 null；DTO 字段保留(下游 COALESCE 引用) */
  name?: string | null;
  specLabel?: string | null;
  recipeType: 'locked' | 'editable' | 'partial';
  sortOrder?: number;
  status?: 'ACTIVE' | 'INACTIVE';
  elements: Array<{
    elementCode: string;
    elementName: string;
    defaultPct: number;
    minPct?: number;
    maxPct?: number;
    isLocked: boolean;
    sortOrder?: number;
  }>;
}

/** 材质库导入结果报告 (对应后端 MaterialImportReportDTO，task-0708) */
export interface MaterialImportReport {
  /** 材质对应元素 数据行数 */
  totalRows: number;
  /** 落库材质数(新增+覆盖) */
  materialsUpserted: number;
  /** 落库元素明细行数 */
  elementRowsInserted: number;
  /** 元素主表新增/更新数 */
  elementMasterUpserted: number;
  /** 跳过行/材质数 */
  skippedRowCount: number;
  /** 逐条跳过原因 */
  skipped: Array<{ sheet: string; row: number; reason: string; raw?: string }>;
  /** 耗时(ms) */
  durationMs: number;
}

export const materialRecipeService = {
  async list(opts?: { withCount?: boolean; keyword?: string }): Promise<MaterialRecipeLite[]> {
    const res = await api.get('/material-recipes', {
      params: {
        ...(opts?.withCount ? { withCount: true } : {}),
        ...(opts?.keyword ? { keyword: opts.keyword } : {}),
      },
    });
    return (res as unknown as MaterialRecipeLite[]) ?? [];
  },

  // ── 材质库导入 / 模板下载 (task-0708 · F1) ──

  /** POST /material-recipes/import — 上传 xlsx 导入材质库(Upsert + 报告) */
  async importLibrary(file: File): Promise<MaterialImportReport> {
    const fd = new FormData();
    fd.append('file', file);
    const res = await api.post('/material-recipes/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res as unknown as MaterialImportReport;
  },

  /**
   * GET /material-recipes/import/template — 下载干净导入模板(xlsx)。
   * 注意: api 响应拦截器已 `return response.data`,故 responseType:'blob' 时返回值本身即 Blob。
   */
  async downloadTemplate(): Promise<Blob> {
    const data: any = await api.get('/material-recipes/import/template', { responseType: 'blob' });
    return data instanceof Blob
      ? data
      : new Blob([data], {
          type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        });
  },

  // ── 材质-料号 绑定关系管理(Phase 1 新增) ──

  /** GET /material-recipes/{id}/parts — 该材质下绑定的料号分页 */
  async listParts(
    id: string,
    params?: { keyword?: string; page?: number; size?: number },
  ): Promise<PageResultLike<MaterialRecipePart>> {
    const res = await api.get(`/material-recipes/${id}/parts`, { params });
    return res as unknown as PageResultLike<MaterialRecipePart>;
  },

  /** POST /material-recipes/{id}/bind-parts — 批量绑定 */
  async bindParts(id: string, partNos: string[]): Promise<{ updated: number }> {
    const res = await api.post(`/material-recipes/${id}/bind-parts`, { partNos });
    return res as unknown as { updated: number };
  },

  /** POST /material-recipes/{id}/unbind-parts — 批量解绑(置 NULL)*/
  async unbindParts(id: string, partNos: string[]): Promise<{ updated: number }> {
    const res = await api.post(`/material-recipes/${id}/unbind-parts`, { partNos });
    return res as unknown as { updated: number };
  },

  /** GET /material-recipes/search-parts — 供"+绑定料号"子 Drawer 搜 mat_part */
  async searchParts(q: string, opts?: { onlyUnbound?: boolean; size?: number }): Promise<MaterialRecipePart[]> {
    const res = await api.get('/material-recipes/search-parts', {
      params: { q, onlyUnbound: opts?.onlyUnbound, size: opts?.size },
    });
    return (res as unknown as MaterialRecipePart[]) ?? [];
  },

  // ── 智能推断(Phase 3/4) ──

  /** GET /material-recipes/suggest-bindings — 扫所有未绑材质料号给出建议 */
  async suggestBindings(): Promise<BindingSuggestion[]> {
    const res = await api.get('/material-recipes/suggest-bindings');
    return (res as unknown as BindingSuggestion[]) ?? [];
  },

  /** POST /material-recipes/confirm-bindings — 批量执行人工确认的绑定 */
  async confirmBindings(items: Array<{ partNo: string; recipeId: string }>): Promise<{ updated: number }> {
    const res = await api.post('/material-recipes/confirm-bindings', { items });
    return res as unknown as { updated: number };
  },

  async detail(id: string): Promise<MaterialRecipeDetail> {
    const res = await api.get(`/material-recipes/${id}`);
    return res as unknown as MaterialRecipeDetail;
  },

  async create(req: MaterialRecipeUpsertRequest): Promise<MaterialRecipeDetail> {
    const res = await api.post('/material-recipes', req);
    return res as unknown as MaterialRecipeDetail;
  },

  async update(id: string, req: MaterialRecipeUpsertRequest): Promise<MaterialRecipeDetail> {
    const res = await api.put(`/material-recipes/${id}`, req);
    return res as unknown as MaterialRecipeDetail;
  },

  async deleteSoft(id: string): Promise<void> {
    await api.delete(`/material-recipes/${id}`);
  },

  async loadForExisting(hfPartNo: string): Promise<ExistingPartMaterial> {
    const res = await api.get(
      `/quotations/configure/existing-part/${encodeURIComponent(hfPartNo)}/material`,
    );
    // api interceptor 已在运行时 unwrap response.data,但 TS 类型仍是 AxiosResponse;
    // 用 unknown 中转避开"AxiosResponse 缺少 ExistingPartMaterial 字段"的严格检查
    return res as unknown as ExistingPartMaterial;
  },
};
