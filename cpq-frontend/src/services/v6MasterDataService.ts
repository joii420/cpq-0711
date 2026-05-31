import api from './api';

// ─── 通用分页结果 ───────────────────────────────────────────────────────────
export interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  page: number;
}

// ─── 工序主数据 DTO（12 字段，1:1 映射后端 ProcessMasterDTO）────────────────
export interface ProcessMasterDTO {
  id: string;
  processNo: string;
  processName: string;
  /** 制造 / 组装 / 电镀 / 外协 / 包装 / 清洗 */
  processCategory?: string;
  isOutsource?: boolean;
  standardCurrency?: string;
  standardUnit?: string;
  defaultDefectRate?: number;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;
}

// ─── BOM 明细 DTO（49 字段，1:1 映射后端 MaterialBomItemDTO）────────────────
export interface MaterialBomItemDTO {
  // 主键 / 审计
  id: string;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;

  // 维度键（系统类型 / 客户 / 料号 / 特征）
  /** QUOTE / PRICING / BOTH */
  systemType: string;
  customerNo: string;
  materialNo: string;
  /** 特征码（可为空） */
  characteristic?: string;

  // 项次 / 工序
  seqNo?: number;
  componentNo?: string;
  partNo?: string;
  effectiveDatetime?: string;
  expireDatetime?: string;
  operationNo?: string;
  operationSeq?: string;
  itemSeq?: number;

  // 用量
  issueUnit?: string;
  compositionQty?: number;
  baseQty?: number;
  componentUsageType?: string;
  featureMgmt?: string;
  upperLimitPct?: number;
  lowerLimitPct?: number;

  // 损耗
  scrapBatch?: number;
  scrapRate?: number;
  fixedScrap?: number;
  scrapRateType?: string;

  // 仓位
  issueLocation?: string;
  issueStorage?: string;

  // 选项
  fasGroup?: string;
  plugPosition?: string;
  refRdCenter?: string;
  isOptional?: boolean;
  woExpandOption?: string;

  // 替代
  isPurchaseReplace?: boolean;
  componentLeadTime?: number;
  mainSubstitute?: string;
  attachedPart?: string;
  ecnNo?: string;

  // 公式
  useQtyFormula?: boolean;
  qtyFormula?: string;

  // 倒冲 / 客供
  isBackflush?: boolean;
  isCustomerSupply?: boolean;
  defectRate?: number;
  calcType?: string;

  // 回收
  recoveryDiscount?: number;
  recoveryCurrency?: string;
  recoveryUnit?: string;
}

// ─── API 方法 ────────────────────────────────────────────────────────────────

// 后端统一返回 { code, message, data } 包装；api.ts interceptor 只解了 axios response.data 一层，
// 业务侧需要再解一层 .data 才能拿到真实 payload（与 boundGlobalVariableService / changeLogService 惯用法一致）
const unwrap = <T>(r: any): T => (r && typeof r === 'object' && 'data' in r ? (r.data as T) : (r as T));

/** 分页查询工序主数据 */
export async function listProcesses(params: {
  keyword?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<ProcessMasterDTO>> {
  const res = await api.get('/v6/process-master', { params });
  return unwrap<PageResult<ProcessMasterDTO>>(res);
}

// ─── 工序主数据 CRUD（新建/编辑/删除）────────────────────────────────────────
/** 新建/编辑 工序请求体。processNo 新建必填且唯一; 编辑时后端锁定(忽略改动)。 */
export interface ProcessMasterUpsert {
  processNo: string;
  processName: string;
  processCategory?: string | null;
  isOutsource?: boolean | null;
  standardCurrency?: string | null;
  standardUnit?: string | null;
  defaultDefectRate?: number | null;
}

/** 新建工序 */
export async function createProcess(req: ProcessMasterUpsert): Promise<ProcessMasterDTO> {
  const res = await api.post('/v6/process-master', req);
  return unwrap<ProcessMasterDTO>(res);
}

/** 编辑工序(processNo 锁定) */
export async function updateProcess(id: string, req: ProcessMasterUpsert): Promise<ProcessMasterDTO> {
  const res = await api.put(`/v6/process-master/${id}`, req);
  return unwrap<ProcessMasterDTO>(res);
}

/** 硬删除工序 */
export async function deleteProcess(id: string): Promise<void> {
  await api.delete(`/v6/process-master/${id}`);
}

/** 分页查询 BOM 明细 */
export async function listBomItems(params: {
  customerNo: string;
  materialNo?: string;
  systemType?: string;
  page?: number;
  size?: number;
}): Promise<PageResult<MaterialBomItemDTO>> {
  const res = await api.get('/v6/material-bom-items', { params });
  return unwrap<PageResult<MaterialBomItemDTO>>(res);
}

/** 获取客户编号下拉列表（用于 BOM 过滤） */
export async function listBomCustomerNos(): Promise<string[]> {
  const res = await api.get('/v6/material-bom-items/customer-nos');
  const data = unwrap<string[]>(res);
  return Array.isArray(data) ? data : [];
}

/** 获取指定客户下的料号下拉列表（最多 500 条，后端截断） */
export async function listBomMaterialNos(customerNo: string): Promise<string[]> {
  const res = await api.get('/v6/material-bom-items/material-nos', { params: { customerNo } });
  const data = unwrap<string[]>(res);
  return Array.isArray(data) ? data : [];
}
