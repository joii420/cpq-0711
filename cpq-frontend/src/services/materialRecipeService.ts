import api from './api';
import type { DecimalString } from '../utils/precision';

/**
 * 材质服务层。
 *
 * ⚠️ task-260901 破坏性变更（见 `dev-docs/task-260901-材质管理模块定义规则更新/api.md` §0）：
 *   BC-1  `MaterialRecipeDetail.elements` 移除 → `configs[]`，元素挂在每个配置下。**不留兼容别名。**
 *   BC-2  新建/编辑请求的 `elements` 移除 → 材质层定的是「有哪些元素」(`composition`)，含量走配置端点。
 *   BC-2b `MaterialRecipeLite.elementCodes` 语义源改为 `material_recipe_composition`
 *         ⇒ **0 配置的材质现在也有元素组成**，前端不得再假设「无配置 ⇒ 无元素」。
 *   BC-3  `ExistingPartMaterial.elements` 语义变为「该料号所用**配置**的元素」，新增 `configNo`。
 *   BC-5  导入报告新增 `createdElements[]` / `createdConfigs[]`。
 *
 * 🚨 所有含量字段一律 `DecimalString`（字符串）传输。`default_pct` 是 `numeric(16,12)`，
 * 走 JS `number` 会丢尾数且无法区分 `12.345678901200` 与 `12.3456789012`。
 */

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

  // ── task-260901 新增 ──
  /** 是否支持自定义含量（材质级开关，M-5）。新建 / 导入自动创建一律默认 false */
  allowCustomContent?: boolean;
  /**
   * 元素组成的符号，按 `material_recipe_composition.sort_order`。
   * ⚠️ BC-2b：与配置无关 —— **0 配置的材质照样有值**。
   */
  elementCodes?: string[];
  /** ACTIVE 含量配置数；0 = 未配置含量 */
  configCount?: number;
}

/** 材质的元素组成项（配置矩阵列的权威来源，M-0） */
export interface CompositionItem {
  /** '10001' —— 权威元素链，指向 element.element_no */
  elementNo: string;
  /** 'Ag' —— 服务端从 element 主表回填的符号快照 */
  elementCode: string;
  /** '银' —— 中文名快照 */
  elementName: string;
  /** 决定配置矩阵的列顺序 */
  sortOrder: number;
}

export interface MaterialRecipeElement {
  /** task-260901 新增：权威元素链（task-260709 B2 已确立 element_no 是权威） */
  elementNo?: string;
  elementCode: string;
  elementName: string;
  /** 100 制、12 位小数字符串，如 '90.000000000000' */
  defaultPct: DecimalString;
  minPct?: DecimalString | null;
  maxPct?: DecimalString | null;
  isLocked: boolean;
  sortOrder: number;
}

/** 含量配置（task-260901 新增的一层：材质 → 配置 → 元素） */
export interface MaterialRecipeConfig {
  id: string;
  /** '00006-01' —— 服务端生成，请求体不得携带（M-1） */
  configNo: string;
  /** 1,2,3… 发号水位（含 INACTIVE），保证编号永不回收 */
  seq: number;
  remark: string | null;
  status: 'ACTIVE' | 'INACTIVE';
  elements: MaterialRecipeElement[];
  /** 合计，100 制、12 位小数字符串，如 '100.000000000000' */
  totalPct: DecimalString;
  createdAt: string;
}

export interface MaterialRecipeDetail extends MaterialRecipeLite {
  specLabel?: string;
  /** 材质的元素组成（矩阵列的权威来源，M-0） */
  composition: CompositionItem[];
  /** false = 该材质已有 ACTIVE 配置，元素组成只读（M-0b） */
  compositionEditable: boolean;
  /** 取代原 `elements`；默认只含 ACTIVE，见 `detail(id, {includeInactiveConfigs})` */
  configs: MaterialRecipeConfig[];
}

/** 「材质管理 → 关联料号」Tab 列表项 (对应后端 MaterialRecipePartDTO) */
export interface MaterialRecipePart {
  partNo: string;
  partName?: string | null;
  specification?: string | null;
  sizeInfo?: string | null;
  productType?: string | null;          // SIMPLE / COMPOSITE
  statusCode?: string | null;           // Y / N
  unitWeight?: DecimalString | null;
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

export interface ExistingPartMaterialElement {
  elementCode: string;
  elementName: string;
  pct: DecimalString;
  minPct: DecimalString | null;
  maxPct: DecimalString | null;
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
  /** BC-3：语义变为「该料号所用**配置**的元素」 */
  elements: ExistingPartMaterialElement[];
  /** BC-3 新增：该料号所用的配置编号 */
  configNo?: string | null;
}

/**
 * 编辑既有材质（`PUT /material-recipes/{id}`）。
 * ⚠️ BC-2：不再带 `elements`；含量一律走 §2.2 的配置端点。
 * `composition` 仅当该材质无 ACTIVE 配置时可变更；否则后端返 409 `COMPOSITION_LOCKED`（M-0b）。
 */
export interface MaterialRecipeUpdateRequest {
  symbol: string;
  name?: string | null;
  specLabel?: string | null;
  recipeType: 'locked' | 'editable' | 'partial';
  allowCustomContent: boolean;
  composition: Array<{ elementNo: string; sortOrder: number }>;
  /**
   * ⚠️ `api.md` §2.1 的 PUT 字段清单未列出 sortOrder / status，但材质编辑抽屉自 task-0708
   * 起就在编辑这两项（状态是唯一可重新启用材质的入口）。继续下发，已报主线确认。
   */
  sortOrder?: number;
  status?: 'ACTIVE' | 'INACTIVE';
}

/** 新建配置时的单个元素条目（`elementCode` / `elementName` 服务端回填，传了也忽略） */
export interface ConfigElementInput {
  elementNo: string;
  /** 去尾随零的写法与补零写法等价（'75' ≡ '75.000000000000'，服务端按 BigDecimal 解析） */
  defaultPct: DecimalString;
}

/**
 * 新建材质（`POST /material-recipes`）——**建材质 + 元素组成 + 全部配置一次调用、一个事务**。
 * 🚫 不要拆成「先建材质再逐条建配置」：中途失败会留下一个没有配置的半成品材质并白占编号。
 * 🚫 请求体不含 `composition` —— 元素组成由服务端从 `configs` 推导（各组元素种类须相同，
 *    取第 1 组的元素与顺序），与导入侧 M-5b 是同一条规则。
 */
export interface MaterialRecipeCreateRequest {
  symbol: string;
  name?: string | null;
  specLabel?: string | null;
  recipeType: 'locked' | 'editable' | 'partial';
  allowCustomContent?: boolean;
  sortOrder?: number;
  status?: 'ACTIVE' | 'INACTIVE';
  /** 必填，至少 1 组 */
  configs: Array<{ remark?: string | null; elements: ConfigElementInput[] }>;
}

/** 含量配置的新建 / 修改请求（POST / PUT 同形，`api.md` §2.2） */
export interface MaterialRecipeConfigUpsertRequest {
  remark?: string | null;
  elements: ConfigElementInput[];
}

/** 材质库导入结果报告 (对应后端 MaterialImportReportDTO，task-260901 改版) */
export interface MaterialImportReport {
  /** 读到的数据行数（不含表头、不含全空行） */
  totalRows: number;
  /** 真正落库的材质数；被整体跳过的材质不计、也不消耗编号 */
  recipesCreated: number;
  /** 新增的含量配置组数 */
  configsCreated: number;
  /** 内容已存在而跳过的配置组数 */
  configsSkippedAsDuplicate: number;
  /** 落库元素明细行数 */
  elementRowsInserted: number;
  /** 本次自动建档的元素，供业务复核（X-2 / AC-6） */
  createdElements: Array<{
    elementNo: string;
    elementCode: string;
    elementName: string;
    sourceRow: number;
    sourceRecipe: string;
  }>;
  /** 新增明细 */
  createdConfigs: Array<{
    recipeCode: string;
    recipeSymbol: string;
    configNo: string;
    summary: string;
    recipeIsNew: boolean;
  }>;
  /** 逐条跳过原因（`reason` 前端原文展示，不做映射） */
  skipped: Array<{ sheet: string; row: number | null; reason: string; raw?: string }>;
  skippedRowCount: number;
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

  // ── 材质库导入 / 模板下载 (task-0708 · F1；task-260901 改单表 4 列) ──

  /** POST /material-recipes/import — 上传 xlsx 导入材质库（只增不改 + 报告） */
  async importLibrary(file: File): Promise<MaterialImportReport> {
    const fd = new FormData();
    fd.append('file', file);
    const res = await api.post('/material-recipes/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
    return res as unknown as MaterialImportReport;
  },

  /**
   * GET /material-recipes/import/template — 下载导入模板(xlsx)。
   * task-260901：单 sheet、4 列（材质 / 组号 / 元素符号 / 含量）。
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

  // ── 材质-料号 绑定关系管理(Phase 1 新增；task-260901 不改，绑定挂材质层) ──

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

  // ── 材质本体 ──

  /** GET /material-recipes/{id} — 详情；默认只返 ACTIVE 配置 */
  async detail(id: string, opts?: { includeInactiveConfigs?: boolean }): Promise<MaterialRecipeDetail> {
    const res = await api.get(`/material-recipes/${id}`, {
      params: opts?.includeInactiveConfigs ? { includeInactiveConfigs: true } : undefined,
    });
    const detail = res as unknown as MaterialRecipeDetail;
    // 防御：后端字段缺省时给空数组，避免渲染层到处 ?. —— 不做语义兜底，只做形状兜底
    return {
      ...detail,
      composition: detail?.composition ?? [],
      configs: detail?.configs ?? [],
    };
  },

  /** POST /material-recipes — 建材质 + 元素组成 + 全部配置（一个事务） */
  async create(req: MaterialRecipeCreateRequest): Promise<MaterialRecipeDetail> {
    const res = await api.post('/material-recipes', req);
    return res as unknown as MaterialRecipeDetail;
  },

  /** PUT /material-recipes/{id} — 编辑材质本体（不含配置） */
  async update(id: string, req: MaterialRecipeUpdateRequest): Promise<MaterialRecipeDetail> {
    const res = await api.put(`/material-recipes/${id}`, req);
    return res as unknown as MaterialRecipeDetail;
  },

  async deleteSoft(id: string): Promise<void> {
    await api.delete(`/material-recipes/${id}`);
  },

  // ── 含量配置 CRUD（task-260901 全新，`api.md` §2.2） ──

  /** GET /material-recipes/{id}/configs — 按 seq 升序 */
  async listConfigs(id: string, opts?: { includeInactive?: boolean }): Promise<MaterialRecipeConfig[]> {
    const res = await api.get(`/material-recipes/${id}/configs`, {
      params: opts?.includeInactive ? { includeInactive: true } : undefined,
    });
    return (res as unknown as MaterialRecipeConfig[]) ?? [];
  },

  /** POST /material-recipes/{id}/configs — 新建配置（configNo 由服务端生成） */
  async createConfig(id: string, req: MaterialRecipeConfigUpsertRequest): Promise<MaterialRecipeConfig> {
    const res = await api.post(`/material-recipes/${id}/configs`, req);
    return res as unknown as MaterialRecipeConfig;
  },

  /** PUT /material-recipes/{id}/configs/{configId} — 改 remark 与 elements（configNo / seq 不可改） */
  async updateConfig(
    id: string,
    configId: string,
    req: MaterialRecipeConfigUpsertRequest,
  ): Promise<MaterialRecipeConfig> {
    const res = await api.put(`/material-recipes/${id}/configs/${configId}`, req);
    return res as unknown as MaterialRecipeConfig;
  },

  /** DELETE /material-recipes/{id}/configs/{configId} — 软删（status → INACTIVE），幂等 */
  async deleteConfig(id: string, configId: string): Promise<void> {
    await api.delete(`/material-recipes/${id}/configs/${configId}`);
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
