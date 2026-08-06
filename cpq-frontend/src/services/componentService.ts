import api from './api';

// ── batch-expand 类型定义 ─────────────────────────────────────────
export interface BatchExpandTask {
  componentId: string;
  customerId?: string | null;
  partNo?: string | null;
  /** 料号版本号（可选）。传入后后端注入 AND part_version=N 过滤，避免历史版本叠加重复。 */
  partVersion?: number | null;
  /**
   * Bug B: lineItemId (item.id || item.tempId || null)。
   * 后端 BatchExpandRequest 同步加此字段；Jackson 默认忽略未知字段，老后端兼容。
   * 用于后端按 lineItemId 隔离同 partNo 多行的展开结果，防止 cache 互相覆盖。
   */
  lineItemId?: string | null;
  /**
   * task-0725: 该 task 所属业务侧，供后端决定是否为本次展开打开报价侧 pending 可见域
   * （QuotePendingScope.open）。'QUOTE' = 报价侧渲染；'COSTING' = 核价侧渲染，不开启
   * pending 可见域。老后端未识别该字段时按 'COSTING' 兜底（保守），不影响兼容性。
   * 仅进请求体，不进任何前端缓存键（driverExpansionKey 不含此维度，见 useDriverExpansions.ts）。
   */
  usage?: 'QUOTE' | 'COSTING';
}

export interface BatchExpandResultItem {
  /** 与后端 cache key 一致: componentId:customerId:partNo，null 填 "_" */
  key: string;
  status: 'OK' | 'ERROR';
  /** 调试: driver 改写后的最终执行 SQL(含 ? + 参数)。请求 debugSql=true 时填充; ERROR 时也带(失败的那条 SQL)。 */
  debugSql?: string | null;
  data?: {
    rowCount: number;
    driverPath?: string;
    rows: Array<{ driverRow: Record<string, any>; basicDataValues: Record<string, any> }>;
    /** 调试: 请求带 debugSql=true 时，driver 改写后的最终执行 SQL（含 ? 占位符 + 参数）。 */
    debugSql?: string | null;
  } | null;
  error?: string | null;
}

/**
 * 构造与后端 cacheKey 一致的字符串，用于匹配 batch 结果。
 * 后端规则: componentId:customerId:partNo:partVersion，null/undefined 填 "_"
 */
export function buildBatchKey(
  componentId: string,
  customerId?: string | null,
  partNo?: string | null,
  partVersion?: number | null,
): string {
  return `${componentId}:${customerId ?? '_'}:${partNo ?? '_'}:${partVersion ?? '_'}`;
}

/**
 * POST /api/cpq/components/batch-expand
 *
 * 设计目标:**一次 HTTP 请求覆盖整个报价单的全部 driver 展开**。
 * 旧策略 CHUNK=100 导致 N=2000 task 拆成 20 个 HTTP — 违背"一次查询"目标。
 * 后端 ComponentDriverService.batchExpand 已按 (componentId, customerId, partVersion) 聚合到
 * IN SQL,单批携带数千 task 对 DB 压力可控。
 *
 * 现策略:CHUNK = 5000(实质"一次性"),正常报价单 1 个 HTTP 完成。
 * 后端 BATCH_MAX 同步从 100 提到 5000。
 *
 * status=ERROR 的条目仍写入结果(data=null),避免调用方反复重试。
 */
export async function batchExpandDriver(
  tasks: BatchExpandTask[],
  debugSql?: boolean,
  signal?: AbortSignal,
): Promise<BatchExpandResultItem[]> {
  if (tasks.length === 0) return [];
  const CHUNK = 5000;
  if (tasks.length <= CHUNK) {
    const resp: any = await api.post('/components/batch-expand', { tasks, debugSql: !!debugSql }, { signal });
    return (resp?.data?.results ?? resp?.results ?? []) as BatchExpandResultItem[];
  }
  // 兜底:极端大批量分片(>5000),正常路径走不到
  const chunks: BatchExpandTask[][] = [];
  for (let i = 0; i < tasks.length; i += CHUNK) {
    chunks.push(tasks.slice(i, i + CHUNK));
  }
  const allResults: BatchExpandResultItem[] = [];
  for (const chunk of chunks) {
    const resp: any = await api.post('/components/batch-expand', { tasks: chunk, debugSql: !!debugSql }, { signal });
    const results: BatchExpandResultItem[] = resp?.data?.results ?? resp?.results ?? [];
    allResults.push(...results);
  }
  return allResults;
}
// ──────────────────────────────────────────────────────────────────

// ── task-0805：公式绑定报告 / 导入预览增强 / 一键固化 类型定义 ──────────────
// 契约来源：dev-docs/task-0805-组件导入导出功能升级/实现计划.md §2（冻结口径，前后端共同依据）。

/**
 * 绑定去向判定。与后端 FormulaCalculator.resolveFormula 的解析口径一一对应——
 * 前端只展示，不得另写一遍回退链判断（那正是 BL-0098 换个层面重演）。
 */
export type FormulaBindingStatus = 'BOUND' | 'RESOLVED_BY_NAME' | 'RESOLVED_BY_POSITION' | 'UNRESOLVABLE';

/**
 * 单条「字段 → 公式」绑定去向。出现在两处：
 * ① 导出 bindingReport.items（含 componentCode/componentName）；
 * ② 导入预览 ComponentPlan.formulaBinding（不含 componentCode/componentName，由所属 ComponentPlan 提供上下文）。
 */
export interface FormulaBindingItem {
  componentCode?: string;
  componentName?: string;
  /** 条件公式内部引用格式为「字段名 › 规则N」/「字段名 › 默认」。 */
  fieldName: string;
  resolvedFormulaId: string | null;
  resolvedFormulaName: string | null;
  status: FormulaBindingStatus;
  /** UNRESOLVABLE 时给人话原因，其余为 null。 */
  message: string | null;
}

/** GET .../export 响应体顶层新增字段（R1）。导出永不因 unboundCount>0 阻断。 */
export interface BindingReport {
  unboundCount: number;
  totalFormulaRefs: number;
  items: FormulaBindingItem[];
}

/** POST .../import 预览响应体新增的全 bundle 绑定汇总。 */
export interface BindingSummary {
  totalFormulaRefs: number;
  bound: number;
  resolvedByName: number;
  resolvedByPosition: number;
  unresolvable: number;
}

/** R5/AC-7：跨组件引用无法重映射清单（老 bundle Item.id 缺失场景）。 */
export interface CrossRefIssue {
  componentCode: string;
  refType: string;
  ref: string;
  reason: 'BUNDLE_MISSING_ITEM_ID' | 'REF_NOT_IN_BUNDLE';
}

/** POST .../admin/formula-binding/consolidate 清单条目状态（与 FormulaBindingStatus 是不同取值域，见实现计划 §2.4）。 */
export type ConsolidateStatus = 'CONSOLIDATED' | 'UNRESOLVABLE' | 'ERROR';

export interface ConsolidateItem {
  componentCode: string;
  componentName?: string;
  fieldName?: string;
  resolvedFormulaId?: string | null;
  resolvedFormulaName?: string | null;
  status: ConsolidateStatus;
  message?: string | null;
}

export interface ConsolidateResult {
  dryRun: boolean;
  /**
   * ⚠️ dryRun=true 时后端恒为 0（只有实际 UPDATE 才会计数，见 FormulaBindingAdminResource#consolidate）。
   * 预览阶段判断"会影响多少组件"须从 items 里按 status=CONSOLIDATED 去重 componentCode 自行统计，
   * 不能直接读这个字段——FormulaBindingConsolidateDrawer 已按此口径实现。
   */
  componentsUpdated: number;
  itemCount: number;
  items: ConsolidateItem[];
}

/**
 * 从导出下载的 blob 文本中解析 bindingReport（F1）。
 * 🔒 解析失败必须静默降级——下载已经成功完成，绝不能让报告解析失败看起来像导出失败。
 */
function tryParseBindingReport(text: string): BindingReport | null {
  try {
    const parsed = JSON.parse(text);
    const report = parsed?.bindingReport;
    if (report && typeof report.unboundCount === 'number' && Array.isArray(report.items)) {
      return report as BindingReport;
    }
    return null;
  } catch {
    return null;
  }
}

export const componentService = {
  listDirectories: (params?: { keyword?: string; includeDisabled?: boolean }) => api.get('/component-directories', { params }) as Promise<any>,
  createDirectory: (data: any) => api.post('/component-directories', data) as Promise<any>,
  updateDirectory: (id: string, data: any) => api.put(`/component-directories/${id}`, data) as Promise<any>,
  deleteDirectory: (id: string) => api.delete(`/component-directories/${id}`) as Promise<any>,
  /**
   * P1: 导出目录直属组件为 JSON bundle 并触发浏览器下载(只读)。
   * 注意: api 响应拦截器已 `return response.data`,故此处返回值**本身**即 Blob(responseType=blob),
   * 不能再取 .data(否则得到 undefined → 文件内容变成字符串 "undefined")。
   *
   * task-0805 F1：额外从下载内容里解析顶层 `bindingReport`（R1，不发第二次请求），
   * 供调用方判断是否提示"存在未绑定公式的字段"。解析失败一律静默降级为 null——
   * 下载本身已经成功，不能因为报告解析问题让导出看起来失败了。
   */
  exportDirectory: async (id: string): Promise<{ bindingReport: BindingReport | null }> => {
    const data: any = await api.get(`/component-directories/${id}/export`, { responseType: 'blob' });
    const blob = data instanceof Blob
      ? data
      : new Blob([typeof data === 'string' ? data : JSON.stringify(data, null, 2)], { type: 'application/json' });
    const filename = `components-${id}.json`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);

    let bindingReport: BindingReport | null = null;
    try {
      const text = await blob.text();
      bindingReport = tryParseBindingReport(text);
    } catch {
      bindingReport = null;
    }
    return { bindingReport };
  },
  /** P2: 导入预览(dry-run,不写库)。bundle=导出的 JSON 对象。 */
  importPreview: (dirId: string, bundle: any, conflictPolicy: string) =>
    api.post(
      `/component-directories/${dirId}/import?conflictPolicy=${encodeURIComponent(conflictPolicy)}`,
      bundle,
    ) as Promise<any>,
  /**
   * P3: 导入提交(单事务,只新增)。ignoreMissingDeps=true 时忽略缺失依赖。
   * task-0805 R3：新增 ignoreUnboundFormulas（形状照抄 ignoreMissingDeps）——
   * 默认 false 时后端 validateExplicitBinding 遇未绑定公式字段仍 400 拒绝；显式 true 才放行。
   */
  importCommit: (
    dirId: string,
    bundle: any,
    conflictPolicy: string,
    ignoreMissingDeps: boolean,
    ignoreUnboundFormulas: boolean = false,
  ) =>
    api.post(
      `/component-directories/${dirId}/import/commit?conflictPolicy=${encodeURIComponent(conflictPolicy)}&ignoreMissingDeps=${ignoreMissingDeps}&ignoreUnboundFormulas=${ignoreUnboundFormulas}`,
      bundle,
    ) as Promise<any>,
  /**
   * task-0805 F3/R4：一键固化公式绑定（dryRun 预览 / dryRun=false 落库），作用域收窄到
   * directoryId 和/或 componentIds（都不传 = 全库，本任务前端只用目录级）。
   * ⚠️ 端点 @RoleAllowed({"SYSTEM_ADMIN"})，SALES_MANAGER 调用会收到 403——
   * 调用方需捕获 e.httpStatus === 403 给出可读提示，不能让页面白屏。
   */
  consolidateFormulaBinding: (params: { dryRun: boolean; directoryId?: string; componentIds?: string[] }) => {
    const qs = new URLSearchParams();
    qs.set('dryRun', String(params.dryRun));
    if (params.directoryId) qs.set('directoryId', params.directoryId);
    if (params.componentIds && params.componentIds.length > 0) qs.set('componentIds', params.componentIds.join(','));
    return api.post(`/admin/formula-binding/consolidate?${qs.toString()}`) as Promise<ConsolidateResult>;
  },
  list: (params: any) => api.get('/components', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/components/${id}`) as Promise<any>,
  /**
   * 创建组件。payload 透传所有字段，含 Phase1-Snapshot 新增的:
   *   rowKeyFields?: string[]  — 与后端 Component.rowKeyFields 字段名对齐
   * 后端 ComponentService.create() 会对多行可编辑组件做硬校验（rowKeyFields 必须声明）。
   */
  create: (data: any) => api.post('/components', data) as Promise<any>,
  /**
   * 更新组件。payload 透传所有字段，含 Phase1-Snapshot 新增的:
   *   rowKeyFields?: string[]  — 与后端 Component.rowKeyFields 字段名对齐
   * 后端 ComponentService.update() 对存量组件走软校验（不满足仅告警，不阻断保存）。
   */
  update: (id: string, data: any) => api.put(`/components/${id}`, data) as Promise<any>,
  /**
   * 行键候选：对每个字段用 basic_data_path 反查 driver 真实列名 + 校验是否可作行键。
   * body.fields 传当前编辑态（支持未保存）。返回 { candidates: RowKeyCandidate[] }。
   */
  rowKeyCandidates: (id: string, body: { dataDriverPath: string; fields: any[] }) =>
    api.post(`/components/${id}/row-key-candidates`, body) as Promise<any>,
  delete: (id: string) => api.delete(`/components/${id}`) as Promise<any>,
  toggleStatus: (id: string) => api.patch(`/components/${id}/toggle-status`) as Promise<any>,
  /**
   * Y1.5: 按组件 dataDriverPath 展开 N 行（单个，兜底场景保留）。
   * 无 dataDriverPath → rowCount=0(前端按单行兜底)
   */
  expandDriver: (id: string, params: { customerId?: string; partNo?: string }) =>
    api.post(`/components/${id}/expand-driver`, params) as Promise<any>,
  /**
   * task-0729 屏 8（api.md §5.2）：迁移期/新建期的元素列绑定推导预填。
   * 推导失败返回空（不报错）；confidence=LOW 时前端需提示人工确认。
   */
  elementBindingSuggest: (id: string) =>
    api.get(`/components/${id}/element-binding-suggest`) as Promise<any>,
};
